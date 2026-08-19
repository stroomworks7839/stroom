/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.shapeshifter.regex.corpus;

import java.util.ArrayList;
import java.util.List;

/**
 * A corpus of realistic patterns, standing in for the production DS3 configurations that are not
 * available here.
 * <p>
 * Written to be <b>unflattering</b>. A corpus assembled by the same hand that wrote the engine
 * will drift toward what the engine happens to do well, so this deliberately includes shapes
 * expected to be rejected outright (backreferences, lookaround, atomic groups) and shapes
 * expected to land on the slow tier (greedy repeats over permissive classes, overlapping
 * alternations). Those are recorded as results, not skipped — a corpus that only contains
 * passing cases measures nothing.
 * <p>
 * Each category pairs its patterns with inputs that include near-misses and non-matches, since
 * agreeing about failure is as much a correctness property as agreeing about success.
 */
public final class PatternCorpus {

    public record Category(String name, List<String> patterns, List<String> inputs) {

    }

    private PatternCorpus() {
    }

    public static List<Category> categories() {
        final List<Category> categories = new ArrayList<>();

        categories.add(new Category("csv", List.of(
                "^([^,]*),([^,]*),([^,]*)$",
                "^([^,]+),([^,]+)$",
                "^([^,]*),([^,]*),([^,]*),([^,]*),([^,]*)$",
                "^(\"[^\"]*\"|[^,]*),(.*)$",
                "^([^\\t]+)\\t([^\\t]+)\\t([^\\t]+)$",
                "^([^;]+);([^;]+);([^;]+)$",
                "^([^|]*)\\|([^|]*)\\|([^|]*)$",
                "([^,]+)(?:,|$)",
                "^(\\d+),([A-Za-z ]+),(\\d+\\.\\d{2})$"),
                List.of(
                        "one,two,three",
                        "one,,three",
                        ",,",
                        "a,b",
                        "one\ttwo\tthree",
                        "one;two;three",
                        "one|two|three",
                        "42,Widget Assembly,19.99",
                        "\"quoted, field\",rest",
                        "unterminated,\"quote",
                        "single")));

        categories.add(new Category("syslog", List.of(
                "^<(\\d{1,3})>(.*)$",
                "^(\\w{3}) +(\\d{1,2}) (\\d{2}:\\d{2}:\\d{2}) (\\S+) ([^:]+): (.*)$",
                "^(\\w{3} +\\d+ \\d{2}:\\d{2}:\\d{2}) (\\S+) (\\S+?)\\[(\\d+)\\]: (.*)$",
                "^(\\S+) (\\S+) (\\S+) (.*)$",
                "(\\w+)\\[(\\d+)\\]",
                "^\\S+ \\S+ \\S+ (\\S+): (.*)$",
                "kernel: \\[ *(\\d+\\.\\d+)\\] (.*)"),
                List.of(
                        "<134>Mar 18 14:30:00 server01 sshd[1234]: Failed password for root",
                        "Mar 18 14:30:00 server01 sshd[1234]: Accepted publickey",
                        "Mar  1 09:05:59 host cron: job finished",
                        "Jan 31 23:59:59 gateway kernel: [ 1234.567890] link up",
                        "not a syslog line at all",
                        "<999>malformed priority")));

        categories.add(new Category("weblog", List.of(
                "^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)\\] \"([^\"]*)\" (\\d{3}) (\\d+|-)$",
                "^(\\S+) - - \\[([^\\]]+)\\] \"(\\w+) (\\S+) ([^\"]*)\" (\\d{3}) (\\S+)$",
                "\"(GET|POST|PUT|DELETE|HEAD|PATCH) ([^ ]+) (HTTP/[0-9.]+)\"",
                "^(\\d+\\.\\d+\\.\\d+\\.\\d+) .* (\\d{3}) (\\d+)$",
                "\\[(\\d{2})/(\\w{3})/(\\d{4}):(\\d{2}):(\\d{2}):(\\d{2}) ([+-]\\d{4})\\]",
                "referer=\"([^\"]*)\" agent=\"([^\"]*)\""),
                List.of(
                        "192.168.1.1 - - [17/Aug/2026:14:30:00 +0000] \"GET /index.html HTTP/1.1\" 200 4213",
                        "10.0.0.5 - - [17/Aug/2026:14:30:00 +0000] \"POST /api/v1/thing HTTP/2\" 201 -",
                        "10.0.0.5 user session [17/Aug/2026:14:30:00 +0000] \"HEAD / HTTP/1.0\" 404 0",
                        "referer=\"http://example.com/a?b=c\" agent=\"Mozilla/5.0 (X11)\"",
                        "malformed line without brackets")));

        categories.add(new Category("keyvalue", List.of(
                "^(\\w+)=(\\w+)$",
                "(\\w+)=(\"[^\"]*\"|\\S*)",
                "^([^=]+)=(.*)$",
                "(\\w+):\\s*(\\S+)",
                "^(\\w+)\\s*=\\s*(.*?)\\s*$",
                "\\b(\\w+)=([^\\s,]+)",
                "\\bword\\b",
                "\\B(\\w+)",
                "^([A-Z_]+)=([0-9]+)$"),
                List.of(
                        "colour=red",
                        "name=\"a value\" size=10 flag=true",
                        "path=/usr/local/bin",
                        "timeout: 30",
                        "MAX_RETRIES=5",
                        "key = spaced value ",
                        "novalue=",
                        "=noname")));

        categories.add(new Category("datetime", List.of(
                "^(\\d{4})-(\\d{2})-(\\d{2})$",
                "^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})$",
                "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?(Z|[+-]\\d{2}:\\d{2})",
                "^(\\d{2})/(\\d{2})/(\\d{4})$",
                "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(AM|PM)?",
                "^(\\w{3}) (\\d{1,2}), (\\d{4})$",
                "^(\\d{10})$",
                "(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d+)"),
                List.of(
                        "2026-08-17",
                        "2026-08-17T14:30:00",
                        "2026-08-17 14:30:00",
                        "2026-08-17T14:30:00.123Z",
                        "2026-08-17T14:30:00+01:00",
                        "17/08/2026",
                        "2:30 PM",
                        "14:30:00.123456",
                        "Aug 17, 2026",
                        "1755438600",
                        "2026-13-45")));

        categories.add(new Category("network", List.of(
                "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$",
                "^(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)$",
                "^(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d{1,2})$",
                "([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}",
                "^([0-9a-fA-F:]+)$",
                "src=(\\S+) dst=(\\S+) sport=(\\d+) dport=(\\d+)",
                "^(tcp|udp|icmp) (\\S+) -> (\\S+)$"),
                List.of(
                        "192.168.1.1",
                        "10.0.0.255:8080",
                        "172.16.0.0/12",
                        "00:1a:2b:3c:4d:5e",
                        "fe80::1",
                        "src=10.0.0.1 dst=10.0.0.2 sport=443 dport=51234",
                        "tcp 10.0.0.1:80 -> 10.0.0.2:443",
                        "999.999.999.999")));

        categories.add(new Category("quoted", List.of(
                "\"([^\"]*)\"",
                "'([^']*)'",
                "^\"([^\"]*)\",\"([^\"]*)\"$",
                "\\[([^\\]]*)\\]",
                "\\(([^)]*)\\)",
                "\\{([^}]*)\\}",
                "<([^>]*)>",
                "^(\"[^\"]*\"|\\S+) (\"[^\"]*\"|\\S+)$"),
                List.of(
                        "say \"hello there\" now",
                        "it's 'quoted' here",
                        "\"a\",\"b\"",
                        "prefix [inside] suffix",
                        "call(arg1, arg2)",
                        "block {body}",
                        "tag <element> end",
                        "\"quoted one\" unquoted",
                        "unbalanced \"quote")));

        categories.add(new Category("numbers", List.of(
                "^-?(\\d+)$",
                "^-?(\\d+)\\.(\\d+)$",
                "^([+-]?\\d*\\.?\\d+)([eE][+-]?\\d+)?$",
                "^0[xX]([0-9a-fA-F]+)$",
                "^(\\d{1,3}(?:,\\d{3})*)$",
                "([£$€])(\\d+\\.\\d{2})",
                "^(\\d+)([KMGT]i?B)$",
                "(\\d+)%"),
                List.of(
                        "42",
                        "-42",
                        "3.14159",
                        "-0.5",
                        "1.6e-19",
                        "0xDEADBEEF",
                        "1,234,567",
                        "$19.99",
                        "512MiB",
                        "87%",
                        "not a number")));

        categories.add(new Category("identifiers", List.of(
                "^([A-Za-z_][A-Za-z0-9_]*)$",
                "^([a-z]+(?:-[a-z]+)*)$",
                "^([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$",
                "^(https?)://([^/]+)(/.*)?$",
                "^(/[^/]+)+/?$",
                "^([A-Za-z]:\\\\.*)$",
                "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
                "^(\\w+)\\.(\\w+)\\.(\\w+)$"),
                List.of(
                        "my_variable1",
                        "kebab-case-name",
                        "user.name+tag@example.co.uk",
                        "https://example.com/path/to/thing",
                        "/usr/local/bin",
                        "C:\\Windows\\System32",
                        "7e38afb4-b3ff-456c-a225-9887ffb6935d",
                        "a.b.c",
                        "not an email@")));

        categories.add(new Category("structured", List.of(
                "^type=(\\S+) msg=audit\\(([^)]+)\\): (.*)$",
                "^EventID=(\\d+)\\s+Level=(\\w+)\\s+Message=(.*)$",
                "^\\s*<(\\w+)>([^<]*)</\\w+>\\s*$",
                "\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"",
                "\"(\\w+)\"\\s*:\\s*(\\d+)",
                "^(\\w+)\\((.*)\\)$",
                "^\\s*(#|//)(.*)$"),
                List.of(
                        "type=EXECVE msg=audit(1755438600.123:456): argc=3 a0=\"ls\"",
                        "EventID=4624 Level=Information Message=An account was logged on",
                        "  <name>value</name>  ",
                        "\"key\" : \"value\"",
                        "\"count\": 42",
                        "function(a, b, c)",
                        "# a comment",
                        "// another comment")));

        categories.add(new Category("fixedwidth", List.of(
                "^(.{8})(.{10})(.{12})$",
                "^(.{4})(.{2})(.{2})$",
                "^([A-Z]{3})(\\d{6})([A-Z ]{10})$",
                "^(\\S{1,10})\\s+(\\S{1,10})\\s+(.*)$",
                "^(.{3})(.*)$"),
                List.of(
                        "20260817SERVER0001 PAYLOAD00000",
                        "20260817",
                        "ABC123456NAME      ",
                        "col1       col2      the rest of it",
                        "abcdefgh",
                        "ab")));

        // Shapes chosen because they stress the engine rather than because they are realistic.
        categories.add(new Category("stress", List.of(
                "^((a|b)(c|d)|(e|f)(g|h))$",
                "^(a(b(c(d(e)?)?)?)?)$",
                "^(?:ab)+c$",
                "^a{2,5}b$",
                "^[a-z]{3,}[0-9]{2,4}$",
                "^(x*)(y*)(z*)$",
                "^(.+):(.+)$",
                "^(.*),(.*)$",
                "(a+)+b",
                "^(\\w+|\\d+|\\s+)+$",
                "^([^\\s]*)\\s+([^\\s]*)$",
                "^(\\S+)(?:\\s+(\\S+))?$",
                "^(ERROR|WARN|INFO|DEBUG|TRACE|FATAL): (.*)$",
                "^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) (.*)$",
                "\\p{L}+",
                "\\p{L}{3}",
                "\\p{Lu}\\p{Ll}+",
                "^\\p{Nd}+$",
                "\\p{IsGreek}+",
                "[\\p{L}\\p{Nd}_]+",
                "[\\u00C0-\\u00FF]+",
                "^([\\u0400-\\u052F]+)$",
                "^(.)(.)(.)$",
                "^\\d{4}(-\\d{2}){2}$"),
                List.of(
                        "ac", "bd", "eg", "fh",
                        "abcde", "abc", "a",
                        "ababc", "aab", "aaaaab",
                        "abc12", "xyz9999",
                        "xxyyzz", "",
                        "a:b:c", "one,two,three",
                        "aaab", "aaaa",
                        "word 42",
                        "ERROR: it broke",
                        "GET /path",
                        "café", "Привет", "Ωμέγα", "Hello", "12345",
                        "xyz", "2026-08-17")));

        // The constructs beyond the RE2 subset, which run on the unbounded backtracker (D27).
        // This category predates that tier: it existed so the then-expected refusals were
        // counted and visible, and the same patterns now keep the fancy tier inside both the
        // correctness corpus and the per-match benchmark.
        categories.add(new Category("fancy", List.of(
                "(\\w+)\\s+\\1",
                "^(.)(.)\\2\\1$",
                "foo(?=bar)",
                "foo(?!bar)",
                "(?<=\\$)\\d+",
                "(?<!x)y",
                "(?>a|ab)c",
                "^a*+b$"),
                List.of(
                        "abc abc",
                        "abba",
                        "foobar",
                        "foobaz",
                        "$100",
                        "xy",
                        "zy",
                        "abc",
                        "aaab",
                        "key=value, other=thing",
                        "a word here",
                        "café")));

        return categories;
    }

    /** Every pattern, flattened. */
    public static List<String> allPatterns() {
        final List<String> patterns = new ArrayList<>();
        categories().forEach(category -> patterns.addAll(category.patterns()));
        return patterns;
    }
}
