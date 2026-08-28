package at.least.conch

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Pre-connect "port knocking" (iOS `PortKnocker` parity): a short, ordered
 * sequence of UDP datagrams a firewall / knock daemon watches for before it
 * opens the SSH port. Knock failures are logged, never fatal — UDP is
 * fire-and-forget, and a misconfigured sequence should still let the user
 * reach the real SSH error instead of a silent one.
 */
object PortKnocker {
    /** Gap between knocks (knock daemons expect ordering), same as iOS. */
    const val GAP_MS = 150L

    /** Payload of each datagram (any bytes work; kept identical to iOS). */
    private val PAYLOAD = "conch".toByteArray(Charsets.US_ASCII)

    /**
     * Sends [ports] to [host] in order. Returns how many datagrams were
     * handed to the socket (a resolve failure aborts the whole sequence:
     * every later knock would fail the same way).
     */
    fun knock(host: String, ports: List<Int>, gapMs: Long = GAP_MS): Int {
        if (host.isBlank() || ports.isEmpty()) return 0
        val address = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return 0
        }
        var sent = 0
        try {
            DatagramSocket().use { socket ->
                ports.filter(::isValidPort).forEachIndexed { i, port ->
                    if (i > 0) Thread.sleep(gapMs)
                    try {
                        socket.send(DatagramPacket(PAYLOAD, PAYLOAD.size, address, port))
                        sent++
                    } catch (_: Exception) {
                        // one lost knock: keep the order going for the rest
                    }
                }
            }
        } catch (_: Exception) {
            // no socket at all (no network): the dial that follows reports it
        }
        return sent
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    /**
     * Editor input → ports. Comma/whitespace separated; non-numeric or
     * out-of-range tokens are dropped (empty result = knocking off).
     */
    fun parse(text: String): List<Int> =
        text.split(',', ' ', '\t', '\n')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter(::isValidPort)

    /** Ports → editor text. */
    fun format(ports: List<Int>): String = ports.joinToString(", ")
}
