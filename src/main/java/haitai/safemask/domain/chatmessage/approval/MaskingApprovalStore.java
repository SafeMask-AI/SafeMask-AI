package haitai.safemask.domain.chatmessage.approval;

import java.time.Duration;
import java.util.Optional;

/** 1회용 마스킹 승인 스냅샷 저장소입니다. */
public interface MaskingApprovalStore {

	String save(MaskingApprovalSnapshot snapshot, Duration ttl);

	Optional<MaskingApprovalSnapshot> find(Long memberId, String approvalId);

	Optional<MaskingApprovalSnapshot> consume(Long memberId, String approvalId);

	void delete(Long memberId, String approvalId);

	void deleteForRoom(Long memberId, Long chatRoomId);
}
