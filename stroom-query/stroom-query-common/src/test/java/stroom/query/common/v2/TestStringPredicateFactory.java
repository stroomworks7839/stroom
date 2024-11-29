package stroom.query.common.v2;

import stroom.datasource.api.v2.FieldType;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.common.v2.ExpressionPredicateBuilder.ValueFunctionFactories;
import stroom.query.common.v2.ExpressionPredicateBuilder.ValueFunctionFactory;
import stroom.util.filter.StringPredicateFactory;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TestStringPredicateFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestStringPredicateFactory.class);

    @TestFactory
    List<DynamicTest> fuzzyMatcherTestFactory() {

        // Each test is run in normal ("foorbar") and negated form ("!foorbar")
        return new ArrayList<>(List.of(
                makeFuzzyMatchTest("Contains",
                        "map",
                        List.of("map",
                                "a map",
                                "mapping"),
                        List.of("maap")),

                makeFuzzyMatchTest("Contains with operator",
                        "\\map",
                        List.of("map",
                                "a map",
                                "mapping"),
                        List.of("maap")),

                makeFuzzyMatchTest("Contains with operator (case sensitive)",
                        "=\\map",
                        List.of("map",
                                "a map",
                                "mapping"),
                        List.of("MAP",
                                "A MAP",
                                "MAAP")),

                makeFuzzyMatchTest("Starts with",
                        "^this_",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed",
                                "THIS_IS_MY_FEED_TOO",
                                "this_is_my_feed_too"),
                        List.of("NOT_THIS_IS_MY_FEED")),

                makeFuzzyMatchTest("Starts with (case sensitive)",
                        "=^this_",
                        List.of("this_is_my_feed",
                                "this_is_my_feed_too"),
                        List.of("THIS_IS_MY_FEED",
                                "THIS_IS_MY_FEED_TOO")),

                makeFuzzyMatchTest("Starts with (caret)",
                        "^^this_",
                        List.of("^THIS_IS_MY_FEED",
                                "^this_is_my_feed",
                                "^THIS_IS_MY_FEED_TOO",
                                "^this_is_my_feed_too"),
                        List.of("NOT_THIS_IS_MY_FEED")),

                makeFuzzyMatchTest("Ends with",
                        "$feed",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED",
                                "so_is_this_is_my_feed"),
                        List.of("THIS_IS_MY_FEED_NOT")),

                makeFuzzyMatchTest("Ends with (case sensitive)",
                        "=$feed",
                        List.of("this_is_my_feed",
                                "so_is_this_is_my_feed"),
                        List.of("THIS_IS_MY_FEED",
                                "SO_IS_THIS_IS_MY_FEED")),

                makeFuzzyMatchTest("Ends with (dollar)",
                        "$feed$",
                        List.of("THIS_IS_MY_FEED$",
                                "this_is_my_feed$",
                                "SO_IS_THIS_IS_MY_FEED$",
                                "so_is_this_is_my_feed$"),
                        List.of("THIS_IS_MY_FEED_NOT")),

                makeFuzzyMatchTest("Exact match",
                        "=this_is_my_feed",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed"),
                        List.of("NOT_THIS_IS_MY_FEED", "NOT_THIS_IS_MY_FEED_NOT", "THIS_IS_MY_FEED_NOT")),

                makeFuzzyMatchTest("Exact match (case sensitive)",
                        "==this_is_my_feed",
                        List.of("this_is_my_feed"),
                        List.of("THIS_IS_MY_FEED")),

                makeFuzzyMatchTest("Chars anywhere 1",
                        "~timf",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED",
                                "timf",
                                "TIMF",
                                "th  i   s i  s m  y feed"),
                        List.of("NOT_THIS_IS_MY_XEED", "fmit", "FMIT")),

                makeFuzzyMatchTest("Chars anywhere 1 (upper case)",
                        "~TIMF",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED",
                                "timf",
                                "TIMF",
                                "th  i   s i  s m  y feed"),
                        List.of("NOT_THIS_IS_MY_XEED", "fmit", "FMIT")),

                makeFuzzyMatchTest("Chars anywhere 2",
                        "~t_i_m_f",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED"),
                        List.of("NOT_THIS_IS_MY_XEED", "timf")),

                makeFuzzyMatchTest("Chars anywhere 2 (upper case)",
                        "~T_I_M_F",
                        List.of("THIS_IS_MY_FEED",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED"),
                        List.of("NOT_THIS_IS_MY_XEED", "timf")),

                makeFuzzyMatchTest("Chars anywhere (numbers)",
                        "~99",
                        List.of("THIS_IS_FEED_99",
                                "99_THIS_IS_FEED",
                                "THIS_IS_99_FEED"),
                        List.of("NOT_THIS_IS_MY_FEED")),

                makeFuzzyMatchTest("Chars anywhere (special chars)",
                        "~(xml)",
                        List.of("Events (XML)",
                                "Events (XML) too",
                                "(XML) Events"),
                        List.of("Events XML")),

                makeFuzzyMatchTest("Word boundary match 1",
                        "?TIMF",
                        List.of("THIS_IS_MY_FEED",
                                "THIS__IS__MY__FEED",
                                "THIS-IS-MY-FEED",
                                "THIS  IS  MY  FEED",
                                "this.is.my.feed",
                                "THIS IS MY FEED",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED",
                                "SO_IS_THIS_IS_MY_FEED_TOO"),
                        List.of("timf", "TIMF")),

                makeFuzzyMatchTest("Word boundary match 2",
                        "?ThIsMF",
                        List.of("THIS_IS_MY_FEED",
                                "THIS-IS-MY-FEED",
                                "THIS IS MY FEED",
                                "this.is.my.feed",
                                "this_is_my_feed",
                                "SO_IS_THIS_IS_MY_FEED"),
                        List.of("TXHIS_IS_MY_FEED", "timf", "TIMF")),

                makeFuzzyMatchTest("Word boundary match 3",
                        "?OTheiMa",
                        List.of("the cat sat on their mat",
                                "on their mat",
                                "Of their magic"),
                        List.of("the cat sat on the mat", "sat on there mat", "ON THE MIX")),

                makeFuzzyMatchTest("Word boundary match 4",
                        "?OTheiMa",
                        List.of("theCatSatOnTheirMat",
                                "TheCatSatOnTheirMat",
                                "OfTheirMagic"),
                        List.of("theCatSatOnTheMat", "satOnThereMat", "OnTheMix", "on their moat")),

                makeFuzzyMatchTest("Word boundary match 5",
                        "?CPSP",
                        List.of("CountPipelineSQLPipe",
                                "CountPipelineSwimPipe"),
                        List.of("CountPipelineSoQueueLongPipe")),

                makeFuzzyMatchTest("Word boundary match 6 (camel + delimited) ",
                        "?JDCN",
                        List.of("stroom.job.db.connection.jdbcDriverClassName"),
                        List.of("stroom.job.db.connection.jdbcDriverPassword")),

                makeFuzzyMatchTest("Word boundary match 7 (camel + delimited) ",
                        "?SJDCJDCN",
                        List.of("stroom.job.db.connection.jdbcDriverClassName"),
                        List.of("stroom.job.db.connection.jdbcDriverPassword")),

                makeFuzzyMatchTest("Word boundary match 8",
                        "?MFN",
                        List.of("MY_FEED NAME"),
                        List.of("MY FEEDNAME")),

                makeFuzzyMatchTest("Word boundary match 9 (one word)",
                        "?A",
                        List.of("alpha",
                                "alpha bravo",
                                "bravo alpha"),
                        List.of("bravo")),

                makeFuzzyMatchTest("Word boundary (brackets)",
                        "?Xml",
                        List.of("Events (XML)",
                                "Events (XML) too",
                                "Events [XML] too",
                                "Events XML",
                                "(XML) Events",
                                "(XML)"),
                        List.of("XXML")),

                makeFuzzyMatchTest("Word boundary match (numbers)",
                        "?A99",
                        List.of("THIS_IS_MY_FEED_a99",
                                "a99_this_is_my_feed",
                                "IS_THIS_IS_a99_FEED"),
                        List.of("TXHIS_IS_MY_FEED", "timf", "TIMF")),

                makeFuzzyMatchTest("Single letter (lower case)",
                        "b",
                        List.of("B", "BCD", "ABC", "b", "bcd", "abc"),
                        List.of("A", "C")),

                makeFuzzyMatchTest("Single letter (upper case)",
                        "B",
                        List.of("B", "BCD", "XX_BCD", "ABC"),
                        List.of("A", "C")),

                makeFuzzyMatchTest("Regex partial match",
                        "/(wo)?man$",
                        List.of("a Man",
                                "MAN",
                                "A Woman",
                                "human"),
                        List.of("A MAN WALKED BY",
                                "WOMAN ",
                                "Manly")),

                makeFuzzyMatchTest("Regex full match",
                        "/^(wo)?man$",
                        List.of("Man",
                                "MAN",
                                "Woman"),
                        List.of("A MAN WALKED BY",
                                "WOMAN ",
                                "human",
                                "Manly")),

//                makeFuzzyMatchTest("Invalid Regex, nothing will match",
//                        "/(wo?man$",
//                        List.of(),
//                        List.of("MAN",
//                                "A MAN",
//                                "A MAN WALKED BY",
//                                "WOMAN")),

                makeFuzzyMatchTest("Regex with null values",
                        "/^man",
                        List.of("MAN"),
                        Arrays.asList(null,
                                "A MAN",
                                "WOMAN")),

                makeFuzzyMatchTest("No user input",
                        "",
                        List.of("B", "BCD", "XX_BCD"),
                        Collections.emptyList()),

                makeFuzzyMatchTest("Null/empty items",
                        "a",
                        List.of("A", "ABCD", "abcd", "dcba"),
                        Arrays.asList("", null))
        ));
    }

    @TestFactory
    List<DynamicTest> comparatorTestFactory() {
        return new ArrayList<>(List.of(
                makeComparatorTest("1",
                        "catmat",
                        List.of(
                                "catmat",
                                "the catmat",
                                "cat mat",
                                "the cat mat",
                                "the cat the mat",
                                "the cat on the mat",
                                "the cat sat on the mat"
                        ))
        ));
    }

    private void doFuzzyMatchTest(final String userInput,
                                  final List<String> expectedMatches,
                                  final List<String> expectedNonMatches) {

        LOGGER.info("Testing input [{}]", userInput);
        final List<String> actualMatches = Stream.concat(expectedMatches.stream(),
                        expectedNonMatches.stream())
                .filter(createPredicate(userInput))
                .collect(Collectors.toList());

        assertThat(actualMatches)
                .containsExactlyInAnyOrderElementsOf(expectedMatches);

        final String negatedInput = StringPredicateFactory.NOT_OPERATOR_STR + userInput;

        LOGGER.info("Testing negated input [{}]", negatedInput);
        final List<String> actualNegatedMatches = Stream.concat(expectedMatches.stream(), expectedNonMatches.stream())
                .filter(createPredicate(negatedInput))
                .collect(Collectors.toList());

        assertThat(actualNegatedMatches)
                .containsExactlyInAnyOrderElementsOf(expectedNonMatches);
    }

    private Predicate<String> createPredicate(final String userInput) {
        final Optional<ExpressionOperator> simpleStringExpressionParser = SimpleStringExpressionParser
                .create(new SingleFieldProvider("test"), userInput, false);
        if (simpleStringExpressionParser.isEmpty()) {
            return string -> true;
        }

        final ValueFunctionFactories<String> queryFieldIndex = fieldName -> new ValueFunctionFactory<>() {
            @Override
            public Function<String, Boolean> createNullCheck() {
                return null;
            }

            @Override
            public Function<String, String> createStringExtractor() {
                return string -> string;
            }

            @Override
            public Function<String, Long> createDateExtractor() {
                return null;
            }

            @Override
            public Function<String, BigDecimal> createNumberExtractor() {
                return null;
            }

            @Override
            public FieldType getFieldType() {
                return FieldType.TEXT;
            }
        };

        final Optional<Predicate<String>> optionalValuesPredicate = ExpressionPredicateBuilder
                .create(simpleStringExpressionParser.orElseThrow(), queryFieldIndex, null);
        return string -> optionalValuesPredicate.orElseThrow().test(string);
    }

    private void doComparatorTest(final String userInput,
                                  final List<String> expectedOrderedValues) {

        LOGGER.info("Testing input [{}]", userInput);

        final Comparator<String> comparator = StringPredicateFactory.createMatchComparator(userInput);
        final List<String> actualOrderedValues = expectedOrderedValues.stream()
                .sorted(comparator)
                .collect(Collectors.toList());

        assertThat(actualOrderedValues)
                .isEqualTo(expectedOrderedValues);
    }

    private DynamicTest makeFuzzyMatchTest(final String testName,
                                           final String userInput,
                                           final List<String> expectedMatches,
                                           final List<String> expectedNonMatches) {
        return DynamicTest.dynamicTest(testName, () ->
                doFuzzyMatchTest(userInput, expectedMatches, expectedNonMatches));
    }

    private DynamicTest makeComparatorTest(final String testName,
                                           final String userInput,
                                           final List<String> expectedOrderedValues) {
        return DynamicTest.dynamicTest(testName, () ->
                doComparatorTest(userInput, expectedOrderedValues));
    }

//    public static void main(String[] args) {
//
//        List<String> classNames;
//        try (ScanResult result = new ClassGraph()
//                .acceptPackages("stroom")
//                .enableClassInfo()
//                .ignoreClassVisibility()
//                .scan()) {
//
//            classNames = result.getAllClasses().stream()
//                    .map(ClassInfo::getName)
//                    .collect(Collectors.toList());
//        }
//
//        final Scanner scanner = new Scanner(System.in);
//        do {
//            System.out.println("Enter your search term:");
//            final String userInput = scanner.nextLine();
//            final Predicate<String> fuzzyMatchPredicate = StringPredicateFactory.createFuzzyMatchPredicate(userInput);
//            final Comparator<String> comparator = StringPredicateFactory.createMatchComparator(userInput);
//
//            final List<String> fullList = classNames.stream()
//                    .filter(fuzzyMatchPredicate)
//                    .sorted(comparator)
//                    .collect(Collectors.toList());
//
//            final String outputStr = fullList.stream()
//                    .limit(20)
//                    .collect(Collectors.joining("\n"));
//
//            System.out.println("Results [" + fullList.size() + "]:\n" + outputStr);
//        } while (scanner.hasNext());
//    }

}
