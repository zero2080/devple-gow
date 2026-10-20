package devple.gow.engine.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_5;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * 선택 기능 노멀맵 (원작 PS2 에 없음, 44 문서). 기본 꺼짐 — 끄면 셰이더 경로가 그대로라 원작 재현 화면과 같다.
 * <pre>
 * 켜기: 환경 변수 GOW_NORMALMAP=on, 실행 중 5 키로 토글.
 * 출처 (텍스처마다, 처음 그릴 때 한 번): ① 모드 계층 normalmaps/&lt;텍스처 키&gt;.png (49 문서 §14, 위 계층 우선)
 *   ② work/normalmaps/&lt;텍스처 키&gt;.png (덮어쓰기) ③ CPU 생성 ({@link NormalMapGen}).
 * 텍스처 키 = WadAssets 텍스처 캐시 키: TXR 이름 (예 TXR_Athn04C012), 태그 지정 사본은 "이름@태그", 애니 프레임은 "이름#프레임".
 * </pre>
 */
public final class NormalMaps {

	/** 생성 경사 배율 (높이 0..1 기준, 텍셀당). */
	public static final float STRENGTH = 4f;
	/** 구운 메시 세부 계수: 밝기 배율 = clamp(1 + k·(N_map·L − N_geom·L), 하한, 상한). */
	public static final float DETAIL_K = 1.0f, DETAIL_LO = 0.6f, DETAIL_HI = 1.4f;
	/** 셰이더 텍스처 유닛 (0 = 색, 1 = 안개 텍스처). */
	public static final int UNIT = 2;

	private static boolean enabled = "on".equalsIgnoreCase(System.getenv("GOW_NORMALMAP"));
	private static boolean keyPrev;
	private static final Path OVERRIDE_DIR = Path.of(System.getProperty("gow.normalmaps", "work/normalmaps"));
	private static long genNanos;
	private static int generated, overridden;

	private NormalMaps() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	/** 5 키 가장자리 → 토글 (렌더 프레임마다 호출). */
	public static void pollToggle(long window) {
		boolean down = glfwGetKey(window, GLFW_KEY_5) == GLFW_PRESS;
		if (down && !keyPrev) {
			enabled = !enabled;
			System.out.println("노멀맵 (원작에 없음) " + (enabled ? "켬" : "끔"));
		}
		keyPrev = down;
	}

	/** 덮어쓰기 파일 경로: 모드 계층 normalmaps/ 에 있으면 그것, 아니면 work/normalmaps (없을 수 있음). */
	public static Path overridePath(String key) {
		return devple.gow.tools.content.Content.find("normalmaps/" + key + ".png").orElse(OVERRIDE_DIR.resolve(key + ".png"));
	}

	/**
	 * 노멀맵 텍스처 만들기 (GL 스레드). 덮어쓰기 PNG 가 있으면 그것, 아니면 원본 ARGB 로 생성.
	 *
	 * @param w/h 업로드 크기 (2 의 거듭제곱 패딩 뒤), sw/sh 원본 크기
	 */
	static Texture build(String key, int w, int h, int sw, int sh, boolean repeat, Supplier<int[]> source) {
		long t0 = System.nanoTime();
		try {
			Path p = overridePath(key);
			if (Files.isRegularFile(p)) {
				try {
					BufferedImage img = ImageIO.read(p.toFile());
					if (img != null) {
						overridden++;
						return Texture.of(img, repeat);
					}
				} catch (IOException e) {
					System.err.println("노멀맵 덮어쓰기 읽기 실패: " + p + " (" + e.getMessage() + ")");
				}
			}
			int[] src = source.get();
			if (src == null) {
				return null;
			}
			int[] padded = src;
			if (w != sw || h != sh) {
				padded = new int[w * h];
				for (int y = 0; y < Math.min(sh, h); y++) {
					System.arraycopy(src, y * sw, padded, y * w, Math.min(sw, w));
				}
			}
			int[] n = NormalMapGen.generate(padded, w, h, repeat, STRENGTH);
			generated++;
			return Texture.ofArgb(w, h, n, repeat);
		} finally {
			genNanos += System.nanoTime() - t0;
		}
	}

	/** 누적 통계 (생성 수, 덮어쓰기 수, 누적 ms). */
	public static String stats() {
		return String.format("생성 %d, 덮어쓰기 %d, 누적 %.1f ms", generated, overridden, genNanos / 1e6);
	}
}
