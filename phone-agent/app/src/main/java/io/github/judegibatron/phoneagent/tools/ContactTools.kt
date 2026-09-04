package io.github.judegibatron.phoneagent.tools

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import io.github.judegibatron.phoneagent.util.Fuzzy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Contact lookup shared by the messaging and calling tools. */
object Contacts {

    data class Number(val number: String, val type: String)

    data class Hit(val name: String, val numbers: List<Number>) {
        fun primary(): Number? = numbers.firstOrNull { it.type.equals("Mobile", ignoreCase = true) } ?: numbers.firstOrNull()
    }

    sealed class Resolution {
        data class Resolved(val name: String, val number: String) : Resolution()
        data class Ambiguous(val message: String) : Resolution()
        data class NotFound(val message: String) : Resolution()
    }

    private val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)

    fun search(context: Context, query: String, limit: Int = 6): List<Hit> {
        val resolver = context.contentResolver
        val byId = LinkedHashMap<Long, Pair<String, MutableList<Number>>>()

        fun collect(cursor: Cursor?) {
            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
                val numberIdx = c.getColumnIndexOrThrow(Phone.NUMBER)
                val typeIdx = c.getColumnIndexOrThrow(Phone.TYPE)
                val labelIdx = c.getColumnIndexOrThrow(Phone.LABEL)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val number = c.getString(numberIdx) ?: continue
                    val typeLabel = Phone.getTypeLabel(context.resources, c.getInt(typeIdx), c.getString(labelIdx)).toString()
                    byId.getOrPut(c.getLong(idIdx)) { name to mutableListOf() }.second.add(Number(number, typeLabel))
                }
            }
        }

        collect(resolver.query(Phone.CONTENT_URI, projection, "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), null))

        if (byId.isEmpty()) {
            // Nothing contained the spoken text literally; rank every contact name fuzzily instead.
            val names = HashMap<Long, String>()
            resolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    names[c.getLong(0)] = name
                }
            }
            val ranked = Fuzzy.rank(query, names.entries, { it.value }, threshold = 0.6).take(limit)
            for ((entry, _) in ranked) {
                collect(resolver.query(Phone.CONTENT_URI, projection, "${Phone.CONTACT_ID} = ?", arrayOf(entry.key.toString()), null))
            }
        }

        return byId.values
            .map { (name, numbers) -> Hit(name, numbers.distinctBy { PhoneNumberUtils.normalizeNumber(it.number) }) }
            .sortedByDescending { Fuzzy.score(query, it.name) }
            .take(limit)
    }

    fun looksLikeNumber(text: String): Boolean =
        text.count { it.isDigit() } >= 5 && text.all { it.isDigit() || it in "+ -().#*" }

    fun resolve(context: Context, to: String): Resolution {
        if (looksLikeNumber(to)) return Resolution.Resolved(to.trim(), PhoneNumberUtils.normalizeNumber(to))
        val hits = search(context, to, 5)
        if (hits.isEmpty()) return Resolution.NotFound("No contact matches '$to'.")
        val scores = hits.map { Fuzzy.score(to, it.name) }
        val unique = hits.size == 1 || (scores[0] >= 0.8 && scores[0] - scores[1] >= 0.15)
        if (unique) {
            val best = hits[0]
            val number = best.primary() ?: return Resolution.NotFound("${best.name} has no phone number saved.")
            return Resolution.Resolved(best.name, PhoneNumberUtils.normalizeNumber(number.number))
        }
        val options = hits.joinToString("; ") { hit -> "${hit.name} (${hit.numbers.joinToString(", ") { "${it.type} ${it.number}" }})" }
        return Resolution.Ambiguous("Several contacts match '$to': $options. Ask the user which one, or pass the exact number.")
    }

    fun describe(hits: List<Hit>): String = hits.joinToString("\n") { hit ->
        "- ${hit.name}: ${hit.numbers.joinToString(", ") { "${it.type} ${it.number}" }}"
    }
}

class FindContactTool : AgentTool(
    ToolSpec(
        name = "find_contact",
        description = "Look up people in the user's contacts by (partial or approximate) name. Returns names with phone numbers.",
        properties = mapOf("query" to prop("string", "Name or part of a name as the user said it")),
        required = listOf("query"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val query = args.str("query") ?: return@withContext ToolOutput.error("query is required")
        val hits = Contacts.search(ctx.context, query)
        if (hits.isEmpty()) ToolOutput.text("No contacts match '$query'.")
        else ToolOutput.text("Matches for '$query':\n" + Contacts.describe(hits))
    }
}

class SendSmsTool : AgentTool(
    ToolSpec(
        name = "send_sms",
        description = "Send an SMS text message. 'to' may be a contact name (resolved automatically) or a phone number. " +
            "The message is sent exactly as given, so make sure the wording is what the user wants.",
        properties = mapOf(
            "to" to prop("string", "Contact name or phone number"),
            "message" to prop("string", "The message text"),
        ),
        required = listOf("to", "message"),
        dangerous = true,
    ),
) {
    override fun confirmPrompt(args: JSONObject): String =
        "Send a text to ${args.str("to") ?: "them"} saying: ${args.str("message") ?: ""}?"

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val to = args.str("to") ?: return@withContext ToolOutput.error("to is required")
        val message = args.str("message") ?: return@withContext ToolOutput.error("message is required")
        when (val resolution = Contacts.resolve(ctx.context, to)) {
            is Contacts.Resolution.Ambiguous -> ToolOutput.text(resolution.message)
            is Contacts.Resolution.NotFound -> ToolOutput.error(resolution.message)
            is Contacts.Resolution.Resolved -> {
                val sms = smsManager(ctx.context) ?: return@withContext ToolOutput.error("This device has no SMS capability.")
                val parts = sms.divideMessage(message)
                if (parts.size <= 1) sms.sendTextMessage(resolution.number, null, message, null, null)
                else sms.sendMultipartTextMessage(resolution.number, null, parts, null, null)
                ToolOutput.text("Sent to ${resolution.name} (${resolution.number}): \"$message\"")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
}

class MakeCallTool : AgentTool(
    ToolSpec(
        name = "make_call",
        description = "Place a phone call to a contact name or phone number.",
        properties = mapOf("to" to prop("string", "Contact name or phone number")),
        required = listOf("to"),
        dangerous = true,
    ),
) {
    override fun confirmPrompt(args: JSONObject): String = "Call ${args.str("to") ?: "them"}?"

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val to = args.str("to") ?: return@withContext ToolOutput.error("to is required")
        when (val resolution = Contacts.resolve(ctx.context, to)) {
            is Contacts.Resolution.Ambiguous -> ToolOutput.text(resolution.message)
            is Contacts.Resolution.NotFound -> ToolOutput.error(resolution.message)
            is Contacts.Resolution.Resolved -> {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(resolution.number)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.context.startActivity(intent)
                ToolOutput.text("Calling ${resolution.name} (${resolution.number}).", suppressFollowUp = true)
            }
        }
    }
}
