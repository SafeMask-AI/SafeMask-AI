package haitai.safemask.domain.airun.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OpenAI의 URL 인용 메타데이터를 화면에서 클릭 가능한 안전한 Markdown 링크로 바꿉니다.
 *
 * <p>모델이 만든 링크 문자열을 신뢰하지 않고 API annotation의 URL만 사용합니다. HTTP(S)가
 * 아니거나 로컬·사설 호스트를 가리키는 URL은 버려 XSS와 내부 주소 노출을 방지합니다.
 */
@Component
public class WebCitationFormatter {

	private static final int MAX_SOURCES = 20;

	public FormattedText format(String text, JsonNode annotations) {
		String safeText = text == null ? "" : text;
		List<Citation> citations = new ArrayList<>();
		Map<String, WebSource> sources = new LinkedHashMap<>();

		if (annotations != null && annotations.isArray()) {
			for (JsonNode annotation : annotations) {
				if (!"url_citation".equals(annotation.path("type").asText())) {
					continue;
				}
				WebSource source = toSafeSource(annotation);
				if (source == null) {
					continue;
				}
				sources.putIfAbsent(source.url(), source);
				int endIndex = annotation.path("end_index").asInt(-1);
				if (endIndex >= 0 && endIndex <= safeText.length() && !splitsSurrogatePair(safeText, endIndex)) {
					citations.add(new Citation(endIndex, source));
				}
				if (sources.size() >= MAX_SOURCES) {
					break;
				}
			}
		}

		// 뒤에서부터 삽입해야 앞쪽 annotation의 문자 인덱스가 변하지 않습니다.
		citations.sort(Comparator.comparingInt(Citation::endIndex).reversed());
		StringBuilder withInlineLinks = new StringBuilder(safeText);
		int previousIndex = -1;
		for (Citation citation : citations) {
			if (citation.endIndex() == previousIndex) {
				continue;
			}
			withInlineLinks.insert(citation.endIndex(), " ([출처](" + markdownUrl(citation.source().url()) + "))");
			previousIndex = citation.endIndex();
		}

		List<WebSource> sourceList = List.copyOf(sources.values());
		if (!sourceList.isEmpty()) {
			withInlineLinks.append("\n\n### 출처\n");
			for (WebSource source : sourceList) {
				withInlineLinks.append("- [")
					.append(escapeMarkdownLabel(source.title()))
					.append("](")
					.append(markdownUrl(source.url()))
					.append(") · ")
					.append(source.domain())
					.append('\n');
			}
		}
		return new FormattedText(withInlineLinks.toString().stripTrailing(), sourceList);
	}

	private boolean splitsSurrogatePair(String text, int index) {
		return index > 0 && index < text.length()
			&& Character.isHighSurrogate(text.charAt(index - 1))
			&& Character.isLowSurrogate(text.charAt(index));
	}

	private WebSource toSafeSource(JsonNode annotation) {
		String rawUrl = annotation.path("url").asText("").trim();
		try {
			URI uri = new URI(rawUrl);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getUserInfo() != null
				|| isPrivateHost(host)) {
				return null;
			}
			String title = normalizeTitle(annotation.path("title").asText(""), host);
			return new WebSource(title, uri.toASCIIString(), host);
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private boolean isPrivateHost(String host) {
		if (host.isBlank() || "localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")
			|| host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home")) {
			return true;
		}
		return "0.0.0.0".equals(host)
			|| host.matches("127(?:\\.\\d{1,3}){3}")
			|| host.matches("10(?:\\.\\d{1,3}){3}")
			|| host.matches("192\\.168(?:\\.\\d{1,3}){2}")
			|| host.matches("172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}")
			|| host.matches("169\\.254(?:\\.\\d{1,3}){2}")
			|| host.matches("100\\.(?:6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])(?:\\.\\d{1,3}){2}")
			|| "::1".equals(host) || host.startsWith("fc") || host.startsWith("fd")
			|| host.matches("fe[89ab].*");
	}

	private String normalizeTitle(String title, String fallback) {
		String normalized = title.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
		if (normalized.isBlank()) {
			return fallback;
		}
		return normalized.substring(0, Math.min(normalized.length(), 200));
	}

	private String escapeMarkdownLabel(String label) {
		return label.replace('[', '(').replace(']', ')');
	}

	private String markdownUrl(String url) {
		return url.replace("(", "%28").replace(")", "%29");
	}

	private record Citation(int endIndex, WebSource source) {
	}

	public record FormattedText(String text, List<WebSource> sources) {
	}
}
