package haitai.safemask.domain.maskingrule.repository;

import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaskingRuleRepository extends JpaRepository<MaskingRule, Long> {

	/**
	 * 마스킹 엔진이 사용할 활성 규칙을 우선순위 오름차순으로 조회합니다.
	 * 우선순위가 낮은(먼저 적용되는) 규칙이 텍스트 구간을 선점하며,
	 * 이후 규칙의 매칭이 이미 선점된 구간과 겹치면 무시됩니다.
	 */
	List<MaskingRule> findByEnabledTrueOrderByPriorityAsc();
}
