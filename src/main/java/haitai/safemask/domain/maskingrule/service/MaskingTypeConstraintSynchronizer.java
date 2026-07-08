package haitai.safemask.domain.maskingrule.service;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Oracle enum 체크 제약을 현재 MaskingType 목록과 동기화합니다.
 *
 * <p>프로젝트에 Flyway/Liquibase가 아직 없기 때문에 enum 상수가 늘어나면 기존 DB의
 * CHECK(type IN (...)) 제약이 새 규칙 시딩을 막을 수 있습니다. 이 컴포넌트는 마스킹 타입
 * 제약만 제한적으로 갱신하고, 기본 규칙 시더보다 먼저 실행됩니다.
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class MaskingTypeConstraintSynchronizer implements ApplicationRunner {

	private static final String RULE_TABLE = "MASK_MASKING_RULE";
	private static final String RULE_CONSTRAINT = "CK_MASK_MASKING_RULE_TYPE";
	private static final String ENTITY_TABLE = "MASK_MASKING_ENTITY";
	private static final String ENTITY_CONSTRAINT = "CK_MASK_MASKING_ENTITY_TYPE";

	private final DataSource dataSource;
	private final JdbcTemplate jdbcTemplate;

	@Override
	public void run(ApplicationArguments args) {
		if (!isOracle()) {
			return;
		}

		synchronize(RULE_TABLE, RULE_CONSTRAINT);
		synchronize(ENTITY_TABLE, ENTITY_CONSTRAINT);
	}

	private boolean isOracle() {
		try (Connection connection = dataSource.getConnection()) {
			String productName = connection.getMetaData().getDatabaseProductName();
			return productName != null && productName.toLowerCase(Locale.ROOT).contains("oracle");
		} catch (SQLException e) {
			log.warn("마스킹 타입 체크 제약 동기화를 위한 DB 제품 확인에 실패했습니다.", e);
			return false;
		}
	}

	private void synchronize(String tableName, String constraintName) {
		if (!tableExists(tableName)) {
			return;
		}

		if (constraintExists(constraintName)) {
			if (constraintAllowsAllTypes(constraintName)) {
				return;
			}
			jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP CONSTRAINT " + constraintName);
		}

		jdbcTemplate.execute("ALTER TABLE " + tableName
			+ " ADD CONSTRAINT " + constraintName
			+ " CHECK (type IN (" + allowedTypes() + "))");
		log.info("마스킹 타입 체크 제약 동기화 완료: {}.{}", tableName, constraintName);
	}

	private boolean tableExists(String tableName) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM USER_TABLES
			WHERE TABLE_NAME = ?
			""", Integer.class, tableName);
		return count != null && count > 0;
	}

	private boolean constraintExists(String constraintName) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM USER_CONSTRAINTS
			WHERE CONSTRAINT_NAME = ?
			""", Integer.class, constraintName);
		return count != null && count > 0;
	}

	private boolean constraintAllowsAllTypes(String constraintName) {
		try {
			String searchCondition = jdbcTemplate.queryForObject("""
				SELECT SEARCH_CONDITION
				FROM USER_CONSTRAINTS
				WHERE CONSTRAINT_NAME = ?
				""", String.class, constraintName);
			if (searchCondition == null) {
				return false;
			}
			return Arrays.stream(MaskingType.values())
				.map(MaskingType::name)
				.allMatch(searchCondition::contains);
		} catch (RuntimeException e) {
			log.warn("마스킹 타입 체크 제약 조건문 확인에 실패해 제약을 재생성합니다. constraint={}",
				constraintName, e);
			return false;
		}
	}

	private String allowedTypes() {
		return Arrays.stream(MaskingType.values())
			.map(type -> "'" + type.name() + "'")
			.collect(Collectors.joining(", "));
	}
}
