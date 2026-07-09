package haitai.safemask.domain.masking.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * SQL/DB 객체 식별자를 추출합니다.
 *
 * <p>1차로 JSqlParser가 이해한 테이블명을 수집하고, 2차로 문자열 리터럴을 제외한 원문 토큰을
 * SQL 문맥에 맞게 스캔합니다. 파서가 방언 차이 때문에 일부 문장을 못 읽어도 보수 스캐너가
 * DML/DDL/프로시저/시퀀스/DB링크 식별자를 보강합니다.
 */
final class SqlIdentifierExtractor {

	private static final Pattern QUALIFIED_COLUMN = Pattern.compile(
		"\\b[A-Za-z_$#][A-Za-z0-9_$#]*\\.[A-Za-z_$#][A-Za-z0-9_$#]*\\b");
	private static final Pattern QUALIFIED_WILDCARD = Pattern.compile(
		"\\b[A-Za-z_$#][A-Za-z0-9_$#]*\\.\\*", Pattern.CASE_INSENSITIVE);
	private static final Pattern TABLE_AFTER_KEYWORD = Pattern.compile(
		"(?i)\\b(FROM|JOIN|UPDATE|INTO|DELETE\\s+FROM|MERGE\\s+INTO|USING|TRUNCATE\\s+TABLE)\\s+"
			+ "([A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?(?:@[A-Za-z_$#][A-Za-z0-9_$#]*)?)");
	private static final Pattern DDL_OBJECT = Pattern.compile(
		"(?i)\\b(CREATE|ALTER|DROP)\\s+(?:OR\\s+REPLACE\\s+)?"
			+ "(TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)\\s+"
			+ "([A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?)");
	private static final Pattern CALL_OBJECT = Pattern.compile(
		"(?i)\\b(CALL|EXEC|EXECUTE)\\s+([A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?)");
	private static final Pattern SEQUENCE_USAGE = Pattern.compile(
		"\\b([A-Za-z_$#][A-Za-z0-9_$#]*)\\.(?:NEXTVAL|CURRVAL)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern UPDATE_SET_CLAUSE = Pattern.compile(
		"(?is)\\bSET\\b(.*?)(?=\\bWHERE\\b|\\bRETURNING\\b|;|$)");
	private static final Pattern ASSIGNED_COLUMN = Pattern.compile(
		"\\b([A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?)\\s*=");
	private static final Pattern INSERT_COLUMNS = Pattern.compile(
		"(?is)\\bINSERT\\s+INTO\\s+[A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?(?:@[A-Za-z_$#][A-Za-z0-9_$#]*)?\\s*\\((.*?)\\)");
	private static final Pattern OUTPUT_ALIAS = Pattern.compile(
		"(?i)\\bAS\\s+([A-Za-z_$#][A-Za-z0-9_$#]*)\\b");
	private static final Pattern CTE_NAME = Pattern.compile(
		"(?i)(?:\\bWITH\\b|,)\\s+([A-Za-z_$#][A-Za-z0-9_$#]*)\\s+AS\\s*\\(");
	private static final Pattern FROM_CLAUSE = Pattern.compile(
		"(?is)\\bFROM\\b(.*?)(?=\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bHAVING\\b|\\bUNION\\b|;|$)");
	private static final Pattern FROM_TABLE_ALIAS = Pattern.compile(
		"(?is)(?:^|,|\\bJOIN\\b)\\s*"
			+ "([A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?(?:@[A-Za-z_$#][A-Za-z0-9_$#]*)?)"
			+ "\\s+(?:AS\\s+)?([A-Za-z_$#][A-Za-z0-9_$#]*)\\b");
	private static final Pattern SUBQUERY_ALIAS = Pattern.compile(
		"(?is)\\)\\s+(?:AS\\s+)?([A-Za-z_$#][A-Za-z0-9_$#]*)\\b");
	private static final Pattern UPDATE_ALIAS = Pattern.compile(
		"(?is)\\bUPDATE\\s+"
			+ "[A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?(?:@[A-Za-z_$#][A-Za-z0-9_$#]*)?"
			+ "\\s+(?:AS\\s+)?([A-Za-z_$#][A-Za-z0-9_$#]*)\\s+SET\\b");
	private static final Pattern MERGE_ALIAS = Pattern.compile(
		"(?is)\\b(?:MERGE\\s+INTO|USING)\\s+"
			+ "[A-Za-z_$#][A-Za-z0-9_$#]*(?:\\.[A-Za-z_$#][A-Za-z0-9_$#]*)?(?:@[A-Za-z_$#][A-Za-z0-9_$#]*)?"
			+ "\\s+(?:AS\\s+)?([A-Za-z_$#][A-Za-z0-9_$#]*)\\b");
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$#][A-Za-z0-9_$#]*");
	private static final Pattern SQL_CONTEXT = Pattern.compile(
		"(?is)\\b(SELECT\\b.+\\bFROM|INSERT\\s+INTO|UPDATE\\b.+\\bSET|DELETE\\s+FROM|MERGE\\s+INTO|"
			+ "CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)|"
			+ "ALTER\\s+(?:TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)|"
			+ "DROP\\s+(?:TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)|"
			+ "TRUNCATE\\s+TABLE|CALL\\b|EXEC(?:UTE)?\\b)");
	private static final Pattern SQL_START_LINE = Pattern.compile(
		"(?i)^\\s*(WITH\\b|SELECT\\b|INSERT\\s+INTO\\b|UPDATE\\b|DELETE\\s+FROM\\b|MERGE\\s+INTO\\b|"
			+ "CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)\\b|"
			+ "ALTER\\s+(?:TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)\\b|"
			+ "DROP\\s+(?:TABLE|VIEW|INDEX|SEQUENCE|SYNONYM|PROCEDURE|FUNCTION|PACKAGE|TRIGGER)\\b|"
			+ "TRUNCATE\\s+TABLE\\b|CALL\\b|EXEC(?:UTE)?\\b)");

	private SqlIdentifierExtractor() {
	}

	static List<Item> extract(String sql) {
		List<Range> ranges = ranges(sql);
		if (ranges.isEmpty()) {
			return List.of();
		}
		List<Item> items = new ArrayList<>();
		for (Range range : ranges) {
			String originalSegment = sql.substring(range.start(), range.end());
			String scrubbedSegment = scrubStringLiterals(originalSegment);
			addParserTables(originalSegment, scrubbedSegment, range.start(), items);
			addMatches(scrubbedSegment, SEQUENCE_USAGE, 1, "SQL 시퀀스명", range.start(), items);
			addMatches(scrubbedSegment, QUALIFIED_COLUMN, 0, "SQL 컬럼/스키마 식별자", range.start(), items);
			addMatches(scrubbedSegment, QUALIFIED_WILDCARD, 0, "SQL 컬럼/스키마 식별자", range.start(), items);
			addMatches(scrubbedSegment, TABLE_AFTER_KEYWORD, 2, "SQL 테이블명(FROM)", range.start(), items);
			addMatches(scrubbedSegment, DDL_OBJECT, 3, "SQL DB 객체명", range.start(), items);
			addMatches(scrubbedSegment, CALL_OBJECT, 2, "SQL 프로시저/함수명", range.start(), items);
			addMatches(scrubbedSegment, CTE_NAME, 1, "SQL CTE명", range.start(), items);
			addMatches(scrubbedSegment, OUTPUT_ALIAS, 1, "SQL 출력 컬럼 별칭", range.start(), items);
			addTableAliases(scrubbedSegment, range.start(), items);
			addUpdateSetColumns(scrubbedSegment, range.start(), items);
			addInsertColumns(scrubbedSegment, range.start(), items);
		}
		return deduplicate(items);
	}

	static boolean hasSqlContext(String sql) {
		return !ranges(sql).isEmpty();
	}

	static List<Range> ranges(String sql) {
		if (sql == null || sql.isBlank()) {
			return List.of();
		}
		String scrubbed = scrubStringLiterals(sql);
		List<Range> ranges = new ArrayList<>();
		boolean inSqlBlock = false;
		int rangeStart = 0;
		int lineStart = 0;

		while (lineStart <= scrubbed.length()) {
			int lineEnd = scrubbed.indexOf('\n', lineStart);
			if (lineEnd == -1) {
				lineEnd = scrubbed.length();
			}
			String line = scrubbed.substring(lineStart, lineEnd);
			String trimmed = line.trim();

			if (!inSqlBlock && SQL_START_LINE.matcher(line).find()) {
				inSqlBlock = true;
				rangeStart = lineStart;
			}

			if (inSqlBlock) {
				if (line.indexOf(';') >= 0) {
					addRangeIfSql(scrubbed, ranges, rangeStart, lineEnd);
					inSqlBlock = false;
				} else if (trimmed.isEmpty() && !nextNonEmptyLineLooksSql(scrubbed, lineEnd + 1)) {
					addRangeIfSql(scrubbed, ranges, rangeStart, lineStart);
					inSqlBlock = false;
				}
			}

			if (lineEnd == scrubbed.length()) {
				break;
			}
			lineStart = lineEnd + 1;
		}

		if (inSqlBlock) {
			addRangeIfSql(scrubbed, ranges, rangeStart, scrubbed.length());
		}
		return List.copyOf(ranges);
	}

	static boolean contains(List<Range> ranges, int index) {
		return ranges.stream().anyMatch(range -> range.contains(index));
	}

	private static void addRangeIfSql(String scrubbed, List<Range> ranges, int start, int end) {
		if (end > start && SQL_CONTEXT.matcher(scrubbed.substring(start, end)).find()) {
			ranges.add(new Range(start, end));
		}
	}

	private static boolean nextNonEmptyLineLooksSql(String text, int start) {
		int lineStart = start;
		while (lineStart < text.length()) {
			int lineEnd = text.indexOf('\n', lineStart);
			if (lineEnd == -1) {
				lineEnd = text.length();
			}
			String trimmed = text.substring(lineStart, lineEnd).trim();
			if (!trimmed.isEmpty()) {
				return isSqlContinuationLine(trimmed);
			}
			lineStart = lineEnd + 1;
		}
		return false;
	}

	private static boolean isSqlContinuationLine(String line) {
		String upper = line.toUpperCase(Locale.ROOT);
		return upper.startsWith(",")
			|| upper.startsWith(")")
			|| upper.startsWith("(")
			|| upper.startsWith("--")
			|| upper.startsWith("/*")
			|| upper.startsWith("*")
			|| upper.startsWith("FROM ")
			|| upper.startsWith("JOIN ")
			|| upper.startsWith("LEFT ")
			|| upper.startsWith("RIGHT ")
			|| upper.startsWith("INNER ")
			|| upper.startsWith("OUTER ")
			|| upper.startsWith("FULL ")
			|| upper.startsWith("CROSS ")
			|| upper.startsWith("WHERE ")
			|| upper.startsWith("AND ")
			|| upper.startsWith("OR ")
			|| upper.startsWith("ON ")
			|| upper.startsWith("GROUP ")
			|| upper.startsWith("ORDER ")
			|| upper.startsWith("HAVING ")
			|| upper.startsWith("UNION ")
			|| upper.startsWith("CONNECT ")
			|| upper.startsWith("START ")
			|| upper.startsWith("MODEL ")
			|| upper.startsWith("OFFSET ")
			|| upper.startsWith("FETCH ")
			|| upper.startsWith("LIMIT ")
			|| upper.startsWith("RETURNING ");
	}

	private static void addParserTables(String original, String scrubbed, int offset, List<Item> items) {
		try {
			Statement statement = CCJSqlParserUtil.parse(original);
			TablesNamesFinder finder = new TablesNamesFinder();
			for (Object tableName : finder.getTableList(statement)) {
				addOccurrences(scrubbed, String.valueOf(tableName), "SQL 테이블명(FROM)", offset, items);
			}
		} catch (Exception ignored) {
			// 방언 차이로 파싱이 실패해도 아래 보수 스캐너가 처리한다.
		}
	}

	private static void addMatches(String text, Pattern pattern, int group, String ruleName, int offset,
		List<Item> items) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			int start = group == 0 ? matcher.start() : matcher.start(group);
			int end = group == 0 ? matcher.end() : matcher.end(group);
			if (start >= 0 && end > start) {
				items.add(new Item(text.substring(start, end), offset + start, offset + end, ruleName));
			}
		}
	}

	private static void addOccurrences(String text, String value, String ruleName, int offset, List<Item> items) {
		if (value == null || value.isBlank()) {
			return;
		}
		Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_$#])" + Pattern.quote(value)
			+ "(?![A-Za-z0-9_$#])", Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			items.add(new Item(text.substring(matcher.start(), matcher.end()), offset + matcher.start(),
				offset + matcher.end(), ruleName));
		}
	}

	private static void addUpdateSetColumns(String text, int offset, List<Item> items) {
		Matcher clauseMatcher = UPDATE_SET_CLAUSE.matcher(text);
		while (clauseMatcher.find()) {
			Matcher columnMatcher = ASSIGNED_COLUMN.matcher(clauseMatcher.group(1));
			while (columnMatcher.find()) {
				int start = clauseMatcher.start(1) + columnMatcher.start(1);
				int end = clauseMatcher.start(1) + columnMatcher.end(1);
				items.add(new Item(text.substring(start, end), offset + start, offset + end, "SQL 컬럼명(UPDATE SET)"));
			}
		}
	}

	private static void addInsertColumns(String text, int offset, List<Item> items) {
		Matcher clauseMatcher = INSERT_COLUMNS.matcher(text);
		while (clauseMatcher.find()) {
			Matcher identifierMatcher = IDENTIFIER.matcher(clauseMatcher.group(1));
			while (identifierMatcher.find()) {
				int start = clauseMatcher.start(1) + identifierMatcher.start();
				int end = clauseMatcher.start(1) + identifierMatcher.end();
				items.add(new Item(text.substring(start, end), offset + start, offset + end, "SQL 컬럼명(INSERT)"));
			}
		}
	}

	private static void addTableAliases(String text, int offset, List<Item> items) {
		Matcher fromClauseMatcher = FROM_CLAUSE.matcher(text);
		while (fromClauseMatcher.find()) {
			String fromClause = fromClauseMatcher.group(1);
			Matcher tableAliasMatcher = FROM_TABLE_ALIAS.matcher(fromClause);
			while (tableAliasMatcher.find()) {
				addAliasIfValid(fromClause, offset + fromClauseMatcher.start(1), tableAliasMatcher, 2, items);
			}
			Matcher subqueryAliasMatcher = SUBQUERY_ALIAS.matcher(fromClause);
			while (subqueryAliasMatcher.find()) {
				addAliasIfValid(fromClause, offset + fromClauseMatcher.start(1), subqueryAliasMatcher, 1, items);
			}
		}
		addMatches(text, UPDATE_ALIAS, 1, "SQL 테이블 별칭", offset, items);
		addMatches(text, MERGE_ALIAS, 1, "SQL 테이블 별칭", offset, items);
	}

	private static void addAliasIfValid(String text, int offset, Matcher matcher, int group, List<Item> items) {
		String alias = matcher.group(group);
		if (isFunctionOrKeyword(alias)) {
			return;
		}
		int start = matcher.start(group);
		int end = matcher.end(group);
		items.add(new Item(text.substring(start, end), offset + start, offset + end, "SQL 테이블 별칭"));
	}

	private static List<Item> deduplicate(List<Item> items) {
		Set<String> seen = new LinkedHashSet<>();
		List<Item> result = new ArrayList<>();
		items.stream()
			.sorted((left, right) -> {
				int byStart = Integer.compare(left.start(), right.start());
				if (byStart != 0) {
					return byStart;
				}
				return Integer.compare(rulePriority(left.ruleName()), rulePriority(right.ruleName()));
			})
			.forEach(item -> {
				String key = item.start() + ":" + item.end();
				if (seen.add(key) && !isFunctionOrKeyword(item.value())) {
					result.add(item);
				}
			});
		return result;
	}

	private static boolean isFunctionOrKeyword(String value) {
		String upper = value.toUpperCase(Locale.ROOT);
		return Set.of("SELECT", "FROM", "WHERE", "JOIN", "SET", "VALUES", "COUNT", "DECODE", "NVL",
			"TO_CHAR", "TO_DATE", "SYSDATE").contains(upper);
	}

	private static int rulePriority(String ruleName) {
			return switch (ruleName) {
				case "SQL 시퀀스명" -> 1;
				case "SQL 테이블명(FROM)", "SQL CTE명", "SQL DB 객체명", "SQL 프로시저/함수명" -> 2;
				case "SQL 테이블 별칭" -> 3;
				case "SQL 컬럼명(INSERT)", "SQL 컬럼명(UPDATE SET)", "SQL 컬럼/스키마 식별자", "SQL 출력 컬럼 별칭" -> 4;
				default -> 9;
			};
	}

	private static String scrubStringLiterals(String text) {
		StringBuilder scrubbed = new StringBuilder(text);
		boolean inString = false;
		for (int i = 0; i < scrubbed.length(); i++) {
			char ch = scrubbed.charAt(i);
			if (ch == '\'') {
				if (inString && i + 1 < scrubbed.length() && scrubbed.charAt(i + 1) == '\'') {
					scrubbed.setCharAt(i + 1, ' ');
					i++;
					continue;
				}
				inString = !inString;
				continue;
			}
			if (inString) {
				scrubbed.setCharAt(i, ' ');
			}
		}
		return scrubbed.toString();
	}

	record Item(String value, int start, int end, String ruleName) {
	}

	record Range(int start, int end) {

		boolean contains(int index) {
			return start <= index && index < end;
		}
	}
}
