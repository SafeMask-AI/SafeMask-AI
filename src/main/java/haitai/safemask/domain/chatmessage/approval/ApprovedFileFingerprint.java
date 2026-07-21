package haitai.safemask.domain.chatmessage.approval;

/** 승인 미리보기와 실제 전송 시 재업로드된 첨부가 같은 파일인지 확인하는 지문입니다. */
public record ApprovedFileFingerprint(String originalName, long size, String sha256) {
}
