package haitai.safemask.domain.chatmessage.approval;

/** 새 미리보기용으로 발급된 1회용 승인 식별자와 스냅샷입니다. */
public record IssuedMaskingApproval(String approvalId, MaskingApprovalSnapshot snapshot) {
}
