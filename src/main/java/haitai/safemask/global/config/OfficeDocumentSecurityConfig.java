package haitai.safemask.global.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.context.annotation.Configuration;

/** 압축 기반 Office 파일의 압축 폭탄과 비정상 대용량 XML 전개를 전역 차단합니다. */
@Configuration
public class OfficeDocumentSecurityConfig {
	private static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;
	private static final long MAX_TEXT_BYTES = 10L * 1024 * 1024;
	@PostConstruct
	void configurePoiZipLimits() {
		ZipSecureFile.setMinInflateRatio(0.01d);
		ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
		ZipSecureFile.setMaxTextSize(MAX_TEXT_BYTES);
	}
}
