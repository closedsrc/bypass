package com.vpn.simple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("SpellCheckingInspection")
class ProfileFilterTest {

    private val profile = """
        proxies:
          - name: "US Premium 1"
            type: ss
            server: us1.example.com
            port: 443
          - name: "Singapore 2"
            type: ss
            server: sg.example.com
            port: 443
          - name: "Japan 3"
            type: vmess
            server: jp.example.com
            port: 443
          - name: "United States Backup"
            type: trojan
            server: us2.example.com
            port: 443
          - name: "America Direct"
            type: ss
            server: us3.example.com
            port: 443

        proxy-groups:
          - name: VoltClash
            type: select
            proxies:
              - All
              - BDCLOUD
              - US Premium 1
          - name: All
            type: load-balance
            strategy: round-robin
            proxies:
              - US Premium 1
              - Singapore 2
              - Japan 3
          - name: BDCLOUD
            type: select
            proxies: ["US Premium 1", "Singapore 2"]

        rules:
          - DOMAIN-SUFFIX,example.com,DIRECT
          - DOMAIN-SUFFIX,keepme.test,Singapore 2
          - GEOIP,CN,DIRECT,no-resolve
          - MATCH,VoltClash
    """.trimIndent()

    private fun lines(out: String) = out.lines()

    @Test
    fun `drops usa proxies and every reference to them`() {
        val result = ProfileFilter.apply(profile)

        assertEquals(listOf("Singapore 2", "Japan 3"), result.kept)
        assertEquals(listOf("US Premium 1", "United States Backup", "America Direct"), result.removed)

        val out = result.yaml
        assertFalse("no USA server may survive", out.contains("us1.example.com"))
        assertFalse(out.contains("us2.example.com"))
        assertFalse(out.contains("us3.example.com"))
        assertFalse(out.contains("US Premium 1"))
        assertFalse(out.contains("United States Backup"))
        assertFalse(out.contains("America Direct"))
        assertFalse(out.contains("United States"))
    }

    @Test
    fun `groups lose only the references that are gone`() {
        val out = ProfileFilter.apply(profile).yaml

        assertTrue(out.contains("- name: VoltClash"))
        assertTrue(out.contains("      - \"All\""))
        assertTrue(out.contains("      - \"BDCLOUD\""))
        // BDCLOUD's inline list keeps its one surviving entry
        assertTrue(out.contains("proxies: [\"Singapore 2\"]"))
        assertTrue(out.contains("      - \"Singapore 2\""))
        assertTrue(out.contains("      - \"Japan 3\""))
    }

    @Test
    fun `catch all points at the generated group`() {
        val out = ProfileFilter.apply(profile).yaml
        assertTrue(out.contains("- MATCH,SimpleVPN"))
        assertFalse(out.contains("MATCH,VoltClash"))
    }

    @Test
    fun `load balancing group carries the surviving nodes in order`() {
        val out = ProfileFilter.apply(profile).yaml
        val group = out.substringAfter("- name: SimpleVPN")
        assertTrue(group.contains("type: load-balance"))
        assertTrue(group.indexOf("Singapore 2") < group.indexOf("Japan 3"))
    }

    @Test
    fun `the group health checks up front so dead nodes are not dialled`() {
        val group = ProfileFilter.apply(profile).yaml.substringAfter("- name: SimpleVPN")

        // lazy defaults to true, which only tests a node once traffic lands on it — the
        // first request to a dead node then fails by timeout.
        assertTrue("must check nodes before they are used", group.contains("lazy: false"))
        assertTrue("must re-check on a timer", Regex("interval: \\d+").containsMatchIn(group))
        assertTrue("must give up on a node fast", Regex("timeout: \\d+").containsMatchIn(group))
        assertTrue("must drop a node after a failed check", Regex("max-failed-times: \\d+").containsMatchIn(group))
        val interval = Regex("interval: (\\d+)").find(group)!!.groupValues[1].toInt()
        assertTrue("300s is too slow to notice a node dying", interval <= 60)
    }

    @Test
    fun `user rules keep their order and their targets`() {
        val out = ProfileFilter.apply(profile).yaml
        val kept = lines(out).filter { it.trimStart().startsWith("- ") && it.contains(",") }
            .map { it.trim() }
        assertTrue(kept.contains("- DOMAIN-SUFFIX,example.com,DIRECT"))
        assertTrue(kept.contains("- DOMAIN-SUFFIX,keepme.test,Singapore 2"))
        assertTrue(kept.contains("- GEOIP,CN,DIRECT,no-resolve"))
    }

    @Test
    fun `each top level key appears exactly once`() {
        val out = ProfileFilter.apply(profile).yaml
        for (key in listOf("proxies:", "proxy-groups:", "rules:")) {
            assertEquals("$key must not be duplicated", 1, lines(out).count { it == key })
        }
    }

    @Test
    fun `a group that loses everything is dropped and its rules repointed`() {
        val input = """
            proxies:
              - name: "US Only 1"
                type: ss
                server: a.example.com
              - name: "Singapore 2"
                type: ss
                server: b.example.com

            proxy-groups:
              - name: US Only
                type: select
                proxies:
                  - US Only 1
              - name: Mixed
                type: select
                proxies:
                  - US Only
                  - Singapore 2

            rules:
              - DOMAIN-SUFFIX,gone.test,US Only
              - MATCH,Mixed
        """.trimIndent()

        val out = ProfileFilter.apply(input).yaml
        assertFalse("the emptied group is gone", out.contains("- name: US Only"))
        assertFalse(out.contains("US Only 1"))
        assertTrue(out.contains("- DOMAIN-SUFFIX,gone.test,SimpleVPN"))
        assertTrue(out.contains("- MATCH,SimpleVPN"))
    }

    @Test
    fun `a rule naming a removed node is repointed`() {
        val input = """
            proxies:
              - name: "US West"
                type: ss
                server: a.example.com
              - name: "Tokyo"
                type: ss
                server: b.example.com

            rules:
              - DOMAIN-SUFFIX,node.test,US West
              - MATCH,Tokyo
        """.trimIndent()

        val out = ProfileFilter.apply(input).yaml
        assertTrue(out.contains("- DOMAIN-SUFFIX,node.test,SimpleVPN"))
        assertTrue(out.contains("- MATCH,SimpleVPN"))
        assertTrue(out.contains("  - MATCH,SimpleVPN") || out.contains("    - MATCH,SimpleVPN"))
    }

    @Test
    fun `rules and dns are added when the profile has none`() {
        val input = """
            proxies:
              - name: "Tokyo"
                type: ss
                server: b.example.com
        """.trimIndent()

        val out = ProfileFilter.apply(input).yaml
        assertTrue(out.contains("proxy-groups:"))
        assertTrue(out.contains("rules:"))
        assertTrue(out.contains("- MATCH,SimpleVPN"))
        assertTrue(out.contains("fake-ip"))
    }

    @Test
    fun `a profile with a dns block keeps the one it has`() {
        val input = """
            proxies:
              - name: "Tokyo"
                type: ss
                server: b.example.com

            dns:
              enable: true
              nameserver:
                - 9.9.9.9

            rules:
              - MATCH,DIRECT
        """.trimIndent()

        val out = ProfileFilter.apply(input).yaml
        assertEquals(1, lines(out).count { it == "dns:" })
        assertTrue(out.contains("- 9.9.9.9"))
        assertFalse(out.contains("fake-ip"))
    }

    @Test
    fun `provider backed groups are never treated as empty`() {
        val input = """
            proxies:
              - name: "US One"
                type: ss
                server: a.example.com
              - name: "Osaka"
                type: ss
                server: b.example.com

            proxy-groups:
              - name: FromProvider
                type: url-test
                use:
                  - some-provider

            rules:
              - MATCH,FromProvider
        """.trimIndent()

        val out = ProfileFilter.apply(input).yaml
        assertTrue(out.contains("- name: FromProvider"))
        assertTrue(out.contains("some-provider"))
    }

    @Test
    fun `only whole words count as usa`() {
        assertTrue(ProfileFilter.isUsaNode("US Premium 1"))
        assertTrue(ProfileFilter.isUsaNode("USA-2"))
        assertTrue(ProfileFilter.isUsaNode("United States Backup"))
        assertTrue(ProfileFilter.isUsaNode("U.S. West"))
        assertTrue(ProfileFilter.isUsaNode("America Direct"))
        assertTrue(ProfileFilter.isUsaNode("🇺🇸 us node"))

        assertFalse(ProfileFilter.isUsaNode("Singapore 2"))
        assertFalse("must not trip on 'us' inside a word", ProfileFilter.isUsaNode("Just Fast"))
        assertFalse(ProfileFilter.isUsaNode("Russia 1"))
        assertFalse(ProfileFilter.isUsaNode("Australia"))
        assertFalse(ProfileFilter.isUsaNode("Tokyo"))
        assertFalse(ProfileFilter.isUsaNode("Japan 3"))
    }

    @Test
    fun `a profile with nothing but usa nodes is refused`() {
        val input = """
            proxies:
              - name: "US One"
                type: ss
                server: a.example.com
              - name: "America Two"
                type: ss
                server: b.example.com
        """.trimIndent()

        val failure = runCatching { ProfileFilter.apply(input) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("USA"))
    }

    @Test
    fun `a profile with no proxies section is refused`() {
        val failure = runCatching { ProfileFilter.apply("rules:\n  - MATCH,DIRECT\n") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `an empty group left by an inline list is dropped`() {
        val input = """
            proxies:
              - name: "US One"
                type: ss
                server: a.example.com
              - name: "Seoul"
                type: ss
                server: b.example.com

            proxy-groups:
              - name: Inline
                type: select
                proxies: ["US One"]
              - name: Keeper
                type: select
                proxies: ["Inline", "Seoul"]

            rules:
              - MATCH,Keeper
        """.trimIndent()

        val out = ProfileFilter.apply(input).yaml
        assertFalse(out.contains("- name: Inline"))
        assertTrue(out.contains("- name: Keeper"))
        assertTrue(out.contains("\"Seoul\""))
        assertTrue(out.contains("- MATCH,SimpleVPN"))
    }
}
