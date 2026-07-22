package haitai.safemask.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

class DarkThemeLogoAssetTest {

	private static final String TRANSPARENT_LOGO = "@{/images/haitai-logo-transparent.png}";

	@Test
	void transparentLogoContainsVisibleAndTransparentPixels() throws IOException {
		ClassPathResource resource = new ClassPathResource("static/images/haitai-logo-transparent.png");
		BufferedImage image;
		try (var input = resource.getInputStream()) {
			image = ImageIO.read(input);
		}

		assertThat(image).isNotNull();
		assertThat(image.getColorModel().hasAlpha()).isTrue();
		assertThat(image.getWidth()).isGreaterThan(image.getHeight());

		int transparentPixels = 0;
		int visiblePixels = 0;
		int whiteLogoPixels = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getRGB(x, y);
				int alpha = argb >>> 24;
				if (alpha == 0) {
					transparentPixels++;
				} else {
					visiblePixels++;
				}
				if (alpha == 255
					&& ((argb >>> 16) & 0xff) == 255
					&& ((argb >>> 8) & 0xff) == 255
					&& (argb & 0xff) == 255) {
					whiteLogoPixels++;
				}
			}
		}

		assertThat(transparentPixels).isGreaterThan(0);
		assertThat(visiblePixels).isGreaterThan(0);
		assertThat(whiteLogoPixels).isGreaterThan(0);
	}

	@Test
	void darkSurfacesUseTransparentLogoWhileSidebarKeepsExistingLogoChip() throws IOException {
		String chatTemplate = new ClassPathResource("templates/chat/main.html")
			.getContentAsString(StandardCharsets.UTF_8);
		String notFoundTemplate = new ClassPathResource("templates/error/404.html")
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(StringUtils.countOccurrencesOf(chatTemplate, TRANSPARENT_LOGO)).isEqualTo(4);
		assertThat(chatTemplate)
			.contains("<span class=\"sidebar-brand-mark\"><img th:src=\"@{/images/haitai-logo.jpg}\"");
		assertThat(notFoundTemplate).contains(TRANSPARENT_LOGO);
	}
}
