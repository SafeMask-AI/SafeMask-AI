package haitai.safemask.domain.masking.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 식별자를 의미 보존형 토큰으로 바꿉니다.
 *
 * <p>일반 PII처럼 [SQL_001]로 모두 뭉개면 쿼리 최적화·리뷰 품질이 크게 떨어지므로,
 * 실제 사내 명칭은 숨기되 ORDER/CUSTOMER 같은 일반 도메인 힌트만 남깁니다.
 * SQL 블록 안에서 추출된 식별자에만 적용되므로 이메일 도메인이나 파일명은 이 생성기를 타지 않습니다.
 */
final class SqlSemanticTokenGenerator {

	private static final String TABLE_RULE = "SQL 테이블명(FROM)";
	private static final String CTE_RULE = "SQL CTE명";
	private static final String ALIAS_RULE = "SQL 테이블 별칭";
	private static final String QUALIFIED_RULE = "SQL 컬럼/스키마 식별자";
	private static final Set<String> SENSITIVE_PERSON_COLUMNS = Set.of(
		"NAME", "CUSTOMER_NAME", "USER_NAME", "MEMBER_NAME", "ACCOUNT_NAME", "EMPLOYEE_NAME");
	private static final Set<String> SENSITIVE_PHONE_COLUMNS = Set.of(
		"PHONE", "PHONE_NO", "TEL_NO", "MOBILE_NO", "HP_NO", "CELL_PHONE");
	private static final Set<String> SENSITIVE_EMAIL_COLUMNS = Set.of(
		"EMAIL", "EMAIL_ADDR", "MAIL", "MAIL_ADDR");
	private static final Set<String> SENSITIVE_ID_COLUMNS = Set.of(
		"RRN", "REG_NO", "RESIDENT_NO", "SSN", "BIRTH_NO");

	private final Map<String, String> tableTokens;
	private final Map<String, String> aliasTokens;
	private final Map<String, String> aliasDomains;
	private final Map<String, String> valueTokens = new HashMap<>();

	private SqlSemanticTokenGenerator(Map<String, String> tableTokens, Map<String, String> aliasTokens,
		Map<String, String> aliasDomains) {
		this.tableTokens = tableTokens;
		this.aliasTokens = aliasTokens;
		this.aliasDomains = aliasDomains;
	}

	static SqlSemanticTokenGenerator from(List<SqlIdentifierExtractor.Item> items) {
		Map<String, String> tableTokens = new LinkedHashMap<>();
		Map<String, String> aliasTokens = new LinkedHashMap<>();
		Map<String, String> aliasDomains = new LinkedHashMap<>();
		int[] tableSequence = {1};

		for (SqlIdentifierExtractor.Item item : items) {
			if (isTableLike(item.ruleName())) {
				tableTokens.computeIfAbsent(item.value(), value -> tableToken(value, tableSequence[0]++));
			}
		}

		List<AliasBinding> aliasBindings = new ArrayList<>();
		String lastTable = null;
		for (SqlIdentifierExtractor.Item item : items) {
			if (isTableLike(item.ruleName())) {
				lastTable = item.value();
				continue;
			}
			if (ALIAS_RULE.equals(item.ruleName()) && lastTable != null) {
				aliasBindings.add(new AliasBinding(item.value(), lastTable, domainOf(lastTable)));
			}
		}

		Map<String, Long> domainCounts = aliasBindings.stream()
			.collect(Collectors.groupingBy(AliasBinding::domain, LinkedHashMap::new, Collectors.counting()));
		Set<String> usedAliasTokens = new HashSet<>();
		for (AliasBinding binding : aliasBindings) {
			if (aliasTokens.containsKey(binding.alias())) {
				continue;
			}
			String candidate = detailedAliasSeed(binding.domain(), binding.table());
			aliasTokens.put(binding.alias(), uniqueAliasToken(candidate, usedAliasTokens));
			aliasDomains.put(binding.alias(), binding.domain());
		}

		return new SqlSemanticTokenGenerator(tableTokens, aliasTokens, aliasDomains);
	}

	String tokenFor(SqlIdentifierExtractor.Item item) {
		String key = item.ruleName() + ":" + item.value();
		return valueTokens.computeIfAbsent(key, ignored -> createToken(item));
	}

	Map<String, String> aliasTokenToOriginal() {
		Map<String, String> mappings = new LinkedHashMap<>();
		aliasTokens.forEach((original, token) -> mappings.put(token, original));
		return mappings;
	}

	private String createToken(SqlIdentifierExtractor.Item item) {
		return switch (item.ruleName()) {
			case TABLE_RULE, CTE_RULE ->
				tableTokens.getOrDefault(item.value(), tableToken(item.value(), tableTokens.size() + 1));
			case ALIAS_RULE -> aliasTokens.getOrDefault(item.value(), genericIdentifier(item.value()));
			case QUALIFIED_RULE -> qualifiedToken(item.value());
			case "SQL 출력 컬럼 별칭" -> outputAliasToken(item.value());
			case "SQL 컬럼명(INSERT)", "SQL 컬럼명(UPDATE SET)" -> columnToken(item.value(), null);
			case "SQL 시퀀스명" -> "SEQ_" + domainOf(item.value());
			case "SQL DB 객체명", "SQL 프로시저/함수명" -> objectToken(item.value());
			default -> genericIdentifier(item.value());
		};
	}

	private static boolean isTableLike(String ruleName) {
		return TABLE_RULE.equals(ruleName) || CTE_RULE.equals(ruleName);
	}

	private String qualifiedToken(String value) {
		if (value.endsWith(".*")) {
			String owner = value.substring(0, value.length() - 2);
			return ownerToken(owner) + ".*";
		}
		int dot = value.lastIndexOf('.');
		if (dot < 0) {
			return genericIdentifier(value);
		}
		String owner = value.substring(0, dot);
		String column = value.substring(dot + 1);
		return ownerToken(owner) + "." + columnToken(column, ownerDomain(owner));
	}

	private String ownerToken(String owner) {
		String alias = aliasTokens.get(owner);
		if (alias != null) {
			return alias;
		}
		String table = tableTokens.get(owner);
		if (table != null) {
			return table;
		}
		return aliasSeed(domainOf(owner));
	}

	private String ownerDomain(String owner) {
		String aliasDomain = aliasDomains.get(owner);
		if (aliasDomain != null) {
			return aliasDomain;
		}
		if (tableTokens.containsKey(owner)) {
			return domainOf(owner);
		}
		return domainOf(owner);
	}

	private static String tableToken(String value, int sequence) {
		String domain = domainOf(value);
		String qualifier = tableQualifierOf(value);
		if ("REF".equals(qualifier)) {
			return "T" + String.format("%02d", sequence) + "_" + domain;
		}
		return "T" + String.format("%02d", sequence) + "_" + domain + "_" + qualifier;
	}

	private static String objectToken(String value) {
		String upper = simpleName(value).toUpperCase(Locale.ROOT);
		if (upper.startsWith("PKG_")) {
			return "PKG_" + domainOf(upper);
		}
		if (upper.startsWith("PRC_") || upper.startsWith("PROC_")) {
			return "PRC_" + domainOf(upper);
		}
		if (upper.startsWith("IDX_")) {
			return "IDX_" + domainOf(upper);
		}
		return "OBJ_" + domainOf(upper);
	}

	private static String columnToken(String column, String ownerDomain) {
		String upper = simpleName(column).toUpperCase(Locale.ROOT);
		if (SENSITIVE_PHONE_COLUMNS.contains(upper) || upper.endsWith("_PHONE") || upper.endsWith("_TEL")) {
			return prefix(ownerDomain, "CUSTOMER") + "_PHONE";
		}
		if (SENSITIVE_EMAIL_COLUMNS.contains(upper) || upper.endsWith("_EMAIL")) {
			return prefix(ownerDomain, "CUSTOMER") + "_EMAIL";
		}
		if (SENSITIVE_PERSON_COLUMNS.contains(upper)) {
			return prefix(ownerDomain, "CUSTOMER") + "_NAME";
		}
		if (SENSITIVE_ID_COLUMNS.contains(upper)) {
			return prefix(ownerDomain, "CUSTOMER") + "_ID_NUMBER";
		}
		return upper;
	}

	private static String outputAliasToken(String alias) {
		String upper = simpleName(alias).toUpperCase(Locale.ROOT);
		if ("CUSTOMER_NAME".equals(upper)) {
			return "CUSTOMER_DISPLAY_NAME";
		}
		if ("SALES_MANAGER_NAME".equals(upper) || "MANAGER_NAME".equals(upper)) {
			return "MANAGER_NAME";
		}
		if (SENSITIVE_PHONE_COLUMNS.contains(upper) || upper.endsWith("_PHONE") || upper.endsWith("_TEL")) {
			return "CUSTOMER_PHONE";
		}
		if (SENSITIVE_EMAIL_COLUMNS.contains(upper) || upper.endsWith("_EMAIL")) {
			return "CUSTOMER_EMAIL";
		}
		if (SENSITIVE_PERSON_COLUMNS.contains(upper)) {
			return "DISPLAY_NAME";
		}
		return columnToken(upper, null);
	}

	private static String prefix(String ownerDomain, String fallback) {
		if (ownerDomain == null || ownerDomain.isBlank() || "GENERIC".equals(ownerDomain)) {
			return fallback;
		}
		return ownerDomain;
	}

	private static String genericIdentifier(String value) {
		return domainOf(value) + "_IDENT";
	}

	private static String uniqueAliasToken(String candidate, Set<String> usedAliasTokens) {
		String normalized = candidate == null || candidate.isBlank() ? "Q" : candidate;
		if (usedAliasTokens.add(normalized)) {
			return normalized;
		}
		int sequence = 2;
		while (!usedAliasTokens.add(normalized + sequence)) {
			sequence++;
		}
		return normalized + sequence;
	}

	private static String aliasSeed(String domain) {
		return switch (domain) {
			case "ORDER" -> "O";
			case "CUSTOMER" -> "C";
			case "USER" -> "U";
			case "PRODUCT" -> "P";
			case "SHIPMENT" -> "S";
			case "CLAIM" -> "CL";
			case "PAYMENT" -> "PY";
			case "INVOICE" -> "I";
			case "EMPLOYEE" -> "EMP";
			default -> "Q";
		};
	}

	private static String detailedAliasSeed(String domain, String tableName) {
		String qualifier = qualifierOf(tableName);
		if ("CUSTOMER".equals(domain) && "REF".equals(qualifier)) {
			return "CUST_MAIN";
		}
		if ("USER".equals(domain) && "REF".equals(qualifier)) {
			return "U_MAIN";
		}
		if ("PRODUCT".equals(domain) && "REF".equals(qualifier)) {
			return "P_MAIN";
		}
		if ("EMPLOYEE".equals(domain) && "REF".equals(qualifier)) {
			return "EMP_MAIN";
		}
		return switch (domain) {
			case "ORDER" -> "O_" + qualifier;
			case "CUSTOMER" -> "CUST_" + qualifier;
			case "USER" -> "U_" + qualifier;
			case "PRODUCT" -> "P_" + qualifier;
			case "SHIPMENT" -> "S_" + qualifier;
			case "CLAIM" -> "CL_" + qualifier;
			case "PAYMENT" -> "PY_" + qualifier;
			case "EMPLOYEE" -> "EMP_" + qualifier;
			default -> aliasSeed(domain) + "_" + qualifier;
		};
	}

	private static String qualifierOf(String tableName) {
		String upper = simpleName(tableName).toUpperCase(Locale.ROOT);
		if (upper.contains("BLACKLIST") || upper.contains("EXCLUDE") || upper.contains("DENY")) {
			return "EXCL";
		}
		if (upper.contains("PROFILE") || upper.contains("INFO")) {
			return "PROFILE";
		}
		if (upper.contains("LOGIN") || upper.contains("AUTH")) {
			return "LOGIN";
		}
		if (upper.contains("CATEGORY") || upper.endsWith("_CAT") || upper.contains("_CAT_")) {
			return "CAT";
		}
		if (upper.contains("DISCOUNT") || upper.contains("_DISC")) {
			return "DISC";
		}
		if (upper.contains("HISTORY") || upper.contains("_HIST")) {
			return "HIST";
		}
		if (upper.contains("RECENT")) {
			return "RECENT";
		}
		if (upper.contains("MASTER") || upper.contains("_MST") || upper.endsWith("_MAIN")) {
			return "MAIN";
		}
		if (upper.contains("DETAIL") || upper.contains("_DTL")) {
			return "DETAIL";
		}
		if (upper.contains("TARGET")) {
			return "DEST";
		}
		if (upper.contains("SOURCE")) {
			return "SRC";
		}
		if (upper.contains("TEMP")) {
			return "TEMP";
		}
		return "REF";
	}

	private static String tableQualifierOf(String tableName) {
		String upper = simpleName(tableName).toUpperCase(Locale.ROOT);
		if (upper.contains("BLACKLIST") || upper.contains("EXCLUDE") || upper.contains("DENY")) {
			return "EXCLUSION";
		}
		if (upper.contains("PROFILE") || upper.contains("INFO")) {
			return "PROFILE";
		}
		if (upper.contains("LOGIN") || upper.contains("AUTH")) {
			return "LOGIN";
		}
		if (upper.contains("CLAIM") || upper.contains("ISSUE")) {
			return "ISSUE";
		}
		if (upper.contains("HISTORY") || upper.contains("_HIST") || upper.contains("LOG")) {
			return "LOG";
		}
		if (upper.contains("POINT") || upper.contains("MILEAGE")) {
			return "POINT";
		}
		if (upper.contains("CATEGORY") || upper.endsWith("_CAT") || upper.contains("_CAT_")) {
			return "CATEGORY";
		}
		if (upper.contains("DISCOUNT") || upper.contains("_DISC")) {
			return "DISCOUNT";
		}
		if (upper.contains("RECENT")) {
			return "RECENT";
		}
		if (upper.contains("MASTER") || upper.contains("_MST") || upper.endsWith("_MAIN")) {
			return "MAIN";
		}
		if (upper.contains("DETAIL") || upper.contains("_DTL")) {
			return "DETAIL";
		}
		if (upper.contains("TARGET")) {
			return "DEST";
		}
		if (upper.contains("SOURCE")) {
			return "SRC";
		}
		if (upper.contains("TEMP")) {
			return "TEMP";
		}
		return "REF";
	}

	private static String domainOf(String value) {
		String upper = simpleName(value).toUpperCase(Locale.ROOT);
		if (upper.contains("ORDER")) {
			return "ORDER";
		}
		if (upper.contains("CUSTOMER") || upper.contains("CUST")) {
			return "CUSTOMER";
		}
		if (upper.contains("MEMBER") || upper.contains("USER") || upper.contains("ACCOUNT")) {
			return "USER";
		}
		if (upper.contains("PRODUCT") || upper.contains("PROD")) {
			return "PRODUCT";
		}
		if (upper.contains("SHIP") || upper.contains("INVOICE")) {
			return "SHIPMENT";
		}
		if (upper.contains("CLAIM")) {
			return "CLAIM";
		}
		if (upper.contains("PAY")) {
			return "PAYMENT";
		}
		if (upper.contains("EMP") || upper.contains("STAFF")) {
			return "EMPLOYEE";
		}
		return "GENERIC";
	}

	private static String simpleName(String value) {
		String withoutDblink = value.replaceFirst("@[A-Za-z_$#][A-Za-z0-9_$#]*$", "");
		int dot = withoutDblink.lastIndexOf('.');
		return dot >= 0 ? withoutDblink.substring(dot + 1) : withoutDblink;
	}

	private record AliasBinding(String alias, String table, String domain) {
	}
}
