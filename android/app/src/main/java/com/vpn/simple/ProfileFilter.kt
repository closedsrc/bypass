package com.vpn.simple

/**
 * Rewrites a Clash profile so no traffic can leave through a USA node.
 *
 * The proxy pool loses its USA entries, every group drops the references to them, groups
 * left with nothing are removed along with the groups and rules that pointed at them, and
 * the catch-all rule is sent to a load-balancing group built from what survived. Rules the
 * user wrote (domain, GEOIP, DIRECT) keep their order and their meaning.
 *
 * All of this works on lines rather than on a YAML model, so the user's formatting,
 * comments and key order come through untouched.
 */
object ProfileFilter {

    private const val GROUP_NAME = "SimpleVPN"

    private val USA = Regex("(?i)(^|[^a-z])(us|usa|u\\.s\\.|united states|america)([^a-z]|$)")
    private val NAME_LINE = Regex("^\\s*-?\\s*name:\\s*\"?([^\"\\n]+?)\"?\\s*$")
    private val LIST_ENTRY = Regex("^(\\s*)-\\s")
    private val TOP_KEY = Regex("^([A-Za-z0-9_-]+):")
    private val RULE_BODY = Regex("^\\s*-\\s*(.+?)\\s*$")

    data class Result(val yaml: String, val kept: List<String>, val removed: List<String>)

    fun isUsaNode(name: String): Boolean = USA.containsMatchIn(name)

    fun apply(yaml: String): Result {
        val lines = yaml.lines().toMutableList()

        // -- 1. which top-level proxies may stay -------------------------------------
        val proxyEntries = entriesIn(lines, "proxies")
            ?: throw IllegalStateException("This profile has no proxies section")
        val kept = ArrayList<String>()
        val removed = LinkedHashSet<String>()
        for (entry in proxyEntries) {
            val name = nameOf(lines, entry) ?: continue
            if (isUsaNode(name)) removed.add(name) else kept.add(name)
        }
        // A pool that is entirely US has nothing Bypass may use, so it refuses rather
        // than quietly sending traffic out of a US exit node.
        if (kept.isEmpty()) throw IllegalStateException("Every proxy in this profile is a USA node")

        // -- 2. drop the USA entries --------------------------------------------------
        deleteEntries(lines, proxyEntries.filter { nameOf(lines, it)?.let(::isUsaNode) == true })

        // -- 3. and every reference to them from a group -------------------------------
        rewriteGroups(lines) { refs -> refs.filterNot { it in removed } }

        // -- 4. a group left with nothing cannot be used, so it goes too, along with
        //       whatever referred to it ------------------------------------------------
        val dropped = LinkedHashSet<String>()
        for (entry in entriesIn(lines, "proxy-groups").orEmpty()) {
            val name = nameOf(lines, entry) ?: continue
            if (listRefs(lines, entry).isEmpty() && !hasDynamicMembers(lines, entry)) dropped.add(name)
        }
        if (dropped.isNotEmpty()) {
            deleteEntries(
                lines,
                entriesIn(lines, "proxy-groups").orEmpty()
                    .filter { nameOf(lines, it)?.let { n -> n in dropped } == true },
            )
            rewriteGroups(lines) { refs -> refs.filterNot { it in dropped } }
        }

        // -- 5. our own group, built from what survived ---------------------------------
        addGroup(lines, kept)

        // -- 6. no rule may still name something that is gone, and the catch-all has to
        //       end up on our group -----------------------------------------------------
        retargetRules(lines, removed + dropped)
        ensureCatchAll(lines)

        // -- 7. with no resolver, nothing inside the tunnel can be reached --------------
        if (lines.none { it.startsWith("dns:") }) {
            lines.add("dns:")
            lines.add("  enable: true")
            lines.add("  enhanced-mode: fake-ip")
            lines.add("  fake-ip-range: 198.18.0.1/16")
            lines.add("  nameserver:")
            lines.add("    - tcp://1.1.1.1")
            lines.add("    - tcp://8.8.8.8")
        }

        return Result(lines.joinToString("\n"), kept, removed.toList())
    }

    // ---------------------------------------------------------------- list surgery

    /** Index ranges of the direct `- ` entries of a top-level block. */
    private fun entriesIn(lines: List<String>, key: String): List<IntRange>? {
        val block = blockRange(lines, key) ?: return null
        val starts = ArrayList<Int>()
        val indents = HashMap<Int, Int>()
        for (i in (block.first + 1)..block.last) {
            val indent = LIST_ENTRY.find(lines[i])?.groupValues?.get(1)?.length ?: continue
            starts.add(i)
            indents[i] = indent
        }
        if (starts.isEmpty()) return emptyList()
        // Only the shallowest items are entries of this block. Anything deeper belongs to
        // the entry above it -- a group's own `proxies:` list, for instance.
        val top = starts.filter { indents[it] == indents.values.min() }
        return top.mapIndexed { i, start -> start..(if (i + 1 < top.size) top[i + 1] - 1 else block.last) }
    }

    private fun blockRange(lines: List<String>, key: String): IntRange? {
        val keys = ArrayList<Pair<Int, String>>()
        lines.forEachIndexed { i, line ->
            if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("-")) {
                TOP_KEY.find(line)?.let { keys.add(i to it.groupValues[1]) }
            }
        }
        val at = keys.indexOfFirst { it.second == key }
        if (at < 0) return null
        val end = if (at + 1 < keys.size) keys[at + 1].first - 1 else lines.size - 1
        return keys[at].first..end
    }

    private fun nameOf(lines: List<String>, entry: IntRange): String? {
        for (i in entry) NAME_LINE.find(lines[i])?.let { return it.groupValues[1].trim() }
        return null
    }

    /** Groups fed by providers instead of by an inline list are never considered empty. */
    private fun hasDynamicMembers(lines: List<String>, entry: IntRange): Boolean = entry.any {
        val t = lines[it].trimStart()
        t.startsWith("use:") || t.startsWith("include-all") || t.startsWith("include-all-proxies")
    }

    /** Proxy names a group lists, whether inline (`proxies: [a, b]`) or as a block list. */
    private fun listRefs(lines: List<String>, entry: IntRange): List<String> {
        for (i in entry) {
            val line = lines[i]
            Regex("^\\s*proxies:\\s*\\[(.*)]\\s*$").find(line)?.let {
                return it.groupValues[1].split(',')
                    .map { ref -> ref.trim().trim('"', '\'') }
                    .filter { ref -> ref.isNotEmpty() }
            }
            if (!Regex("^\\s*proxies:\\s*$").containsMatchIn(line)) continue
            val base = line.indexOfFirst { !it.isWhitespace() }
            val refs = ArrayList<String>()
            var j = i + 1
            while (j <= entry.last) {
                val item = lines[j]
                if (item.isBlank()) { j++; continue }
                if (item.indexOfFirst { !it.isWhitespace() } <= base) break
                val marker = LIST_ENTRY.find(item) ?: break
                refs.add(item.substring(marker.range.last + 1).trim().trim('"', '\''))
                j++
            }
            return refs
        }
        return emptyList()
    }

    private fun writeListRefs(lines: MutableList<String>, entry: IntRange, refs: List<String>) {
        for (i in entry) {
            val line = lines[i]
            if (Regex("^\\s*proxies:\\s*\\[.*]\\s*$").containsMatchIn(line)) {
                lines[i] = line.substringBefore("proxies:") +
                    "proxies: [" + refs.joinToString(", ") { "\"$it\"" } + "]"
                return
            }
            if (!Regex("^\\s*proxies:\\s*$").containsMatchIn(line)) continue
            val keyIndent = line.indexOfFirst { !it.isWhitespace() }
            val stale = ArrayList<Int>()
            var j = i + 1
            while (j <= entry.last) {
                val item = lines[j]
                if (item.isBlank()) { j++; continue }
                if (item.indexOfFirst { !it.isWhitespace() } <= keyIndent) break
                if (LIST_ENTRY.containsMatchIn(item)) stale.add(j)
                j++
            }
            for (k in stale.reversed()) lines.removeAt(k)
            lines.addAll(i + 1, refs.map { " ".repeat(keyIndent + 2) + "- \"$it\"" })
            return
        }
    }

    private fun deleteEntries(lines: MutableList<String>, entries: List<IntRange>) {
        for (entry in entries.sortedByDescending { it.first }) {
            for (i in entry.reversed()) lines.removeAt(i)
        }
    }

    /**
     * Applies [fix] to the first group that needs it, then starts over, so index ranges are
     * recomputed after each edit instead of going stale when lines are added or removed.
     */
    private fun rewriteGroups(lines: MutableList<String>, fix: (List<String>) -> List<String>) {
        var guard = 0
        while (guard++ < 1000) {
            val entry = entriesIn(lines, "proxy-groups").orEmpty().firstOrNull { candidate ->
                fix(listRefs(lines, candidate)).size != listRefs(lines, candidate).size
            } ?: return
            writeListRefs(lines, entry, fix(listRefs(lines, entry)))
        }
    }

    /** Merges our load-balancing group into the profile's own proxy-groups list. */
    private fun addGroup(lines: MutableList<String>, nodes: List<String>) {
        val block = blockRange(lines, "proxy-groups")
        if (block == null) {
            lines.add("proxy-groups:")
            lines.addAll(groupEntry("  ", nodes))
            return
        }
        val entryPad = entriesIn(lines, "proxy-groups").orEmpty().firstOrNull()
            ?.let { LIST_ENTRY.find(lines[it.first])?.groupValues?.get(1) }
            ?.takeIf { it.isNotEmpty() } ?: "  "
        var at = block.last + 1
        while (at - 1 > block.first && lines[at - 1].isBlank()) at--
        lines.addAll(at, groupEntry(entryPad, nodes))
    }

    /**
     * The group every connection ends up on.
     *
     * `lazy: false` is the point of it. mihomo's default only health-checks a node when a
     * connection lands on it, so with a pool this size the first request to a dead node
     * discovers it by timing out. Checking up front and on a timer means a node that stops
     * answering leaves the rotation before it is dialled, and comes back once it answers
     * again. `max-failed-times: 1` drops a node after a single failed check rather than
     * letting it fail live traffic twice more first.
     */
    private fun groupEntry(pad: String, nodes: List<String>): List<String> {
        val inner = pad + "  "
        return buildList {
            add("$pad- name: $GROUP_NAME")
            add("${inner}type: load-balance")
            add("${inner}strategy: round-robin")
            add("${inner}lazy: false")
            add("${inner}url: http://www.gstatic.com/generate_204")
            add("${inner}interval: 30")
            add("${inner}timeout: 4000")
            add("${inner}expected-status: 204")
            add("${inner}max-failed-times: 1")
            add("${inner}proxies:")
            for (node in nodes) add("$inner  - \"${node.replace("\"", "\\\"")}\"")
        }
    }

    /**
     * Sends any rule whose target is gone to our group. Rule shape is TYPE,PAYLOAD,TARGET
     * for everything except the catch-all, which is MATCH,TARGET.
     */
    private fun retargetRules(lines: MutableList<String>, gone: Set<String>) {
        val block = blockRange(lines, "rules") ?: return
        for (i in (block.first + 1)..block.last) {
            val body = RULE_BODY.find(lines[i])?.groupValues?.get(1) ?: continue
            val parts = body.split(',').map { it.trim().trim('"', '\'') }.toMutableList()
            val targetAt = when {
                parts.size >= 2 && parts[0].equals("MATCH", true) -> 1
                parts.size >= 3 -> 2
                else -> continue
            }
            if (parts[targetAt] !in gone) continue
            parts[targetAt] = GROUP_NAME
            lines[i] = lines[i].substringBefore(body) + parts.joinToString(",")
        }
    }

    /** The rules have to end with a catch-all pointing at our group. */
    private fun ensureCatchAll(lines: MutableList<String>) {
        val block = blockRange(lines, "rules")
        if (block == null) {
            lines.add("rules:")
            lines.add("  - MATCH,$GROUP_NAME")
            return
        }
        for (i in (block.first + 1)..block.last) {
            val body = RULE_BODY.find(lines[i])?.groupValues?.get(1) ?: continue
            val parts = body.split(',').map { it.trim().trim('"', '\'') }.toMutableList()
            if (parts.size >= 2 && parts[0].equals("MATCH", true)) {
                parts[1] = GROUP_NAME
                lines[i] = lines[i].substringBefore(body) + parts.joinToString(",")
                return
            }
        }
        val pad = entriesIn(lines, "rules").orEmpty().firstOrNull()
            ?.let { LIST_ENTRY.find(lines[it.first])?.groupValues?.get(1) }
            ?.takeIf { it.isNotEmpty() } ?: "  "
        var at = block.last + 1
        while (at - 1 > block.first && lines[at - 1].isBlank()) at--
        lines.add(at, "$pad- MATCH,$GROUP_NAME")
    }
}
