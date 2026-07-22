package haitai.safemask.domain.maskingrule.repository;

import haitai.safemask.domain.maskingrule.entity.MaskingRuleAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaskingRuleAuditRepository extends JpaRepository<MaskingRuleAudit, Long> {

	/** Oracle 버전에 따라 FETCH FIRST 바인드가 거부될 수 있어 ROWNUM으로 상한을 고정합니다. */
	@Query(value = "SELECT * FROM ("
		+ "SELECT mra.* FROM MASK_MASKING_RULE_AUDIT mra "
		+ "WHERE mra.MASKING_RULE_ID = :ruleId ORDER BY mra.CHANGED_AT DESC"
		+ ") WHERE ROWNUM <= 50", nativeQuery = true)
	List<MaskingRuleAudit> findRecentByMaskingRuleId(@Param("ruleId") Long maskingRuleId);
}
