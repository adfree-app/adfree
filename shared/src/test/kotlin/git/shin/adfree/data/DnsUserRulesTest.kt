package git.shin.adfree.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The DNS rule editor hands the core whatever the user typed. What it must not
 * hand over is a blank line, a comment, or a rule twice: the first two compile
 * to nothing and the third compiles twice, and all three would show up in the
 * count the screen prints back.
 */
class DnsUserRulesTest {

    @Test
    fun blanks_and_comments_do_not_reach_the_core() {
        val cleaned = cleanDnsUserRules(
            listOf(
                "||ads.example.test^",
                "",
                "   ",
                "! a note to self",
                "# a hosts-style comment",
                "||tracker.example.test^",
            ),
        )

        assertEquals(
            listOf("||ads.example.test^", "||tracker.example.test^"),
            cleaned,
        )
    }

    @Test
    fun a_rule_written_twice_is_compiled_once() {
        val cleaned = cleanDnsUserRules(
            listOf(
                "||ads.example.test^",
                "  ||ads.example.test^  ",
                "||other.example.test^",
            ),
        )

        assertEquals(listOf("||ads.example.test^", "||other.example.test^"), cleaned)
    }

    @Test
    fun the_order_the_user_wrote_survives() {
        val cleaned = cleanDnsUserRules(
            listOf("||b.example.test^", "||a.example.test^", "@@ok.example.test"),
        )

        assertEquals(
            listOf("||b.example.test^", "||a.example.test^", "@@ok.example.test"),
            cleaned,
        )
    }

    /**
     * The shipped default is AdGuard's own optimized DNS list; a typo here
     * would leave a brand-new install with a filter that never downloads.
     */
    @Test
    fun the_shipped_default_is_adguards_optimized_dns_filter() {
        assertEquals(
            "https://filters.adtidy.org/android/filters/15_optimized.txt",
            AdGuardRepository.DNS_DEFAULT_URL,
        )
    }
}
