package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

class HostGroupingTest {
    private fun host(alias: String, group: String = "", hostname: String = "h", username: String = "u") =
        Host(id = alias, alias = alias, hostname = hostname, username = username, group = group)

    @Test
    fun `ungrouped first in stored order, then groups alphabetically`() {
        val hosts = listOf(
            host("z", "Prod"),
            host("b"),
            host("a", "Dev"),
            host("c", " Prod "),
            host("d"),
        )
        val sections = HostGrouping.sections(hosts)
        assertEquals(listOf(null, "Dev", "Prod"), sections.map { it.title })
        assertEquals(listOf("b", "d"), sections[0].hosts.map { it.alias })
        assertEquals(listOf("a"), sections[1].hosts.map { it.alias })
        assertEquals(listOf("z", "c"), sections[2].hosts.map { it.alias })
    }

    @Test
    fun `no ungrouped section when every host has a group`() {
        val sections = HostGrouping.sections(listOf(host("a", "G")))
        assertEquals(listOf("G"), sections.map { it.title })
    }

    @Test
    fun `group names are distinct trimmed and sorted`() {
        val names = HostGrouping.groupNames(listOf(host("a", "b "), host("b", ""), host("c", "a"), host("d", "b")))
        assertEquals(listOf("a", "b"), names)
    }

    @Test
    fun `filter matches alias host user or group case-insensitively`() {
        val hosts = listOf(
            host("web", hostname = "10.0.0.1", username = "root", group = "Prod"),
            host("db", hostname = "db.internal", username = "postgres", group = "Prod"),
            host("pi", hostname = "pi.local", username = "pi"),
        )
        assertEquals(listOf("web"), HostGrouping.filter(hosts, "WEB").map { it.alias })
        assertEquals(listOf("db"), HostGrouping.filter(hosts, "internal").map { it.alias })
        assertEquals(listOf("db"), HostGrouping.filter(hosts, "postgres").map { it.alias })
        assertEquals(listOf("web", "db"), HostGrouping.filter(hosts, "prod").map { it.alias })
        assertEquals(hosts, HostGrouping.filter(hosts, "  "))
        assertEquals(emptyList<Host>(), HostGrouping.filter(hosts, "nope"))
    }
}
