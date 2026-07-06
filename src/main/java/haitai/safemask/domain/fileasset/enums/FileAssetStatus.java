package haitai.safemask.domain.fileasset.enums;

/**
 * 업로드된 파일의 처리 단계입니다.
 */
public enum FileAssetStatus {

	/** 업로드 완료, 아직 처리 전 */
	UPLOADED,

	/** 파일에서 텍스트 추출 완료 */
	EXTRACTED,

	/** 민감정보 탐지·마스킹 완료 */
	MASKED,

	/** AI 응답에서 생성된 파일 (사용자 업로드가 아닌 서비스 산출물, 다운로드 대상) */
	GENERATED,

	/** 처리 실패 (추출 불가, 지원하지 않는 형식 등) */
	FAILED,

	/** 삭제됨 (soft delete) */
	DELETED
}
