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
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$#][A-Za-z0-9_$#]*");

	private SqlIdentifierExtractor() {
	}

	static List<Item> extract(String sql) {
		String scrubbed = scrubStringLiterals(sql);
		List<Item> items = new ArrayList<>();
		addParserTables(sql, scrubbed, items);
		addMatches(scrubbed, SEQUENCE_USAGE, 1, "SQL 시퀀스명", items);
		addMatches(scrubbed, QUALIFIED_COLUMN, 0, "SQL 컬럼/스키마 식별자", items);
		addMatches(scrubbed, TABLE_AFTER_KEYWORD, 2, "SQL 테이블명(FROM)", items);
		addMatches(scrubbed, DDL_OBJECT, 3, "SQL DB 객체명", items);
		addMatches(scrubbed, CALL_OBJECT, 2, "SQL 프로시저/함수명", items);
		addUpdateSetColumns(scrubbed, items);
		addInsertColumns(scrubbed, items);
		return deduplicate(items);
	}

	private static void addParserTables(String original, String scrubbed, List<Item> items) {
		try {
			Statement statement = CCJSqlParserUtil.parse(original);
			TablesNamesFinder finder = new TablesNamesFinder();
			for (Object tableName : finder.getTableList(statement)) {
				addOccurrences(scrubbed, String.valueOf(tableName), "SQL 테이블명(FROM)", items);
			}
		} catch (Exception ignored) {
			// 방언 차이로 파싱이 실패해도 아래 보수 스캐너가 처리한다.
		}
	}

	private static void addMatches(String text, Pattern pattern, int group, String ruleName, List<Item> items) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			int start = group == 0 ? matcher.start() : matcher.start(group);
			int end = group == 0 ? matcher.end() : matcher.end(group);
			if (start >= 0 && end > start) {
				items.add(new Item(text.substring(start, end), start, end, ruleName));
			}
		}
	}

	private static void addOccurrences(String text, String value, String ruleName, List<Item> items) {
		if (value == null || value.isBlank()) {
			return;
		}
		Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_$#])" + Pattern.quote(value)
			+ "(?![A-Za-z0-9_$#])", Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			items.add(new Item(text.substring(matcher.start(), matcher.end()), matcher.start(), matcher.end(), ruleName));
		}
	}

	private static void addUpdateSetColumns(String text, List<Item> items) {
		Matcher clauseMatcher = UPDATE_SET_CLAUSE.matcher(text);
		while (clauseMatcher.find()) {
			Matcher columnMatcher = ASSIGNED_COLUMN.matcher(clauseMatcher.group(1));
			while (columnMatcher.find()) {
				int start = clauseMatcher.start(1) + columnMatcher.start(1);
				int end = clauseMatcher.start(1) + columnMatcher.end(1);
				items.add(new Item(text.substring(start, end), start, end, "SQL 컬럼명(UPDATE SET)"));
			}
		}
	}

	private static void addInsertColumns(String text, List<Item> items) {
		Matcher clauseMatcher = INSERT_COLUMNS.matcher(text);
		while (clauseMatcher.find()) {
			Matcher identifierMatcher = IDENTIFIER.matcher(clauseMatcher.group(1));
			while (identifierMatcher.find()) {
				int start = clauseMatcher.start(1) + identifierMatcher.start();
				int end = clauseMatcher.start(1) + identifierMatcher.end();
				items.add(new Item(text.substring(start, end), start, end, "SQL 컬럼명(INSERT)"));
			}
		}
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
			case "SQL 테이블명(FROM)", "SQL DB 객체명", "SQL 프로시저/함수명" -> 2;
			case "SQL 컬럼명(INSERT)", "SQL 컬럼명(UPDATE SET)", "SQL 컬럼/스키마 식별자" -> 3;
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
}
