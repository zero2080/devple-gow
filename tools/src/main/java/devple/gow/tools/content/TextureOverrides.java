package devple.gow.tools.content;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 텍스처 PNG 교체 (49 문서 §14). 계층의 PNG 가 WAD 텍스처 해독 결과 대신 쓰인다.
 * <pre>
 *   textures/&lt;WAD이름&gt;/&lt;TXR이름&gt;.png      그 WAD 에서만 (WAD 이름은 .WAD 뺀 것, 예 textures/ATHN01A/TXR_Athn01A003.png)
 *   textures/&lt;TXR이름&gt;.png                모든 WAD
 *   …/&lt;TXR이름&gt;.&lt;프레임&gt;.png             애니메이션 텍스처 프레임 하나 (없으면 &lt;TXR이름&gt;.png 세로 스트립을 프레임 수로 자름)
 * </pre>
 * 크기는 자유 ({@link #fit}: 원본과 같은 UV 가 같은 그림을 가리키게 맞춤). 색·알파는 일반 8비트 ARGB — 해독기 출력과 같다
 * (PS2 알파 0x80 = 불투명은 해독기가 이미 255 로 늘림). 같은 이름이 WAD 별·전역 둘 다 있으면 WAD 별이 이긴다.
 */
public final class TextureOverrides {

	public static final String DIR = "textures";

	/** "wad/이름" (전역은 "/이름", 소문자, .png 뺌) → 파일. 위 계층이 이긴다. */
	private static final LayerCache<Map<String, Path>> TABLE = new LayerCache<>(c -> scan());

	private TextureOverrides() {
	}

	private static Map<String, Path> scan() {
		Map<String, Path> m = new HashMap<>();
		for (Path root : Content.findAll(DIR)) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> s = Files.list(root)) {
				for (Path f : s.sorted().toList()) {
					if (Files.isDirectory(f)) {
						try (Stream<Path> s2 = Files.list(f)) {
							String wad = f.getFileName().toString();
							s2.filter(TextureOverrides::isPng).sorted().forEach(g -> m.put(key(wad, stem(g)), g));
						}
					} else if (isPng(f)) {
						m.put(key("", stem(f)), f);
					}
				}
			} catch (IOException e) {
				System.err.println("[mods] " + root + " 목록 실패: " + e.getMessage());
			}
		}
		return Map.copyOf(m);
	}

	private static boolean isPng(Path f) {
		return Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
	}

	private static String stem(Path f) {
		String n = f.getFileName().toString();
		return n.substring(0, n.length() - 4);
	}

	private static String key(String wad, String name) {
		return (wad + "/" + name).toLowerCase(Locale.ROOT);
	}

	/** WAD 파일 이름 (ATHN01A.WAD) → 디렉터리 이름 (ATHN01A). */
	public static String wadKey(String wadFile) {
		int dot = wadFile.lastIndexOf('.');
		return (dot > 0 ? wadFile.substring(0, dot) : wadFile).toUpperCase(Locale.ROOT);
	}

	/** 교체 파일이 하나라도 있는가. */
	public static boolean any() {
		return !TABLE.get().isEmpty();
	}

	/** 교체 PNG 경로 (WAD 별 → 전역). frame &lt; 0 = 텍스처 전체. 없으면 null. */
	public static Path find(String wadFile, String txrName, int frame) {
		Map<String, Path> m = TABLE.get();
		if (m.isEmpty() || txrName == null) {
			return null;
		}
		String n = frame >= 0 ? txrName + "." + frame : txrName;
		Path p = wadFile != null ? m.get(key(wadKey(wadFile), n)) : null;
		return p != null ? p : m.get(key("", n));
	}

	/** 교체 PNG (없거나 못 읽으면 null). */
	public static BufferedImage load(String wadFile, String txrName, int frame) {
		Path p = find(wadFile, txrName, frame);
		if (p == null) {
			return null;
		}
		try {
			BufferedImage img = ImageIO.read(p.toFile());
			if (img == null) {
				System.err.println("[mods] PNG 아님: " + p);
			}
			return img;
		} catch (IOException e) {
			System.err.println("[mods] " + p + " 읽기 실패: " + e.getMessage());
			return null;
		}
	}

	/**
	 * 교체 그림을 원본 (ow×oh) 자리에 맞춘 ARGB 그림. 원본은 업로드 때 2 의 거듭제곱 (PW×PH) 으로 패딩되고 UV 도 그 기준이다
	 * (엔진 Texture.of). 교체 그림이 원본과 같은 크기면 그대로 (같은 패딩), 다르면 같은 배율의 2 의 거듭제곱 텍스처 (tw×th) 의 왼쪽 위
	 * ow/PW·tw × oh/PH·th 자리에 넣는다 (정수배면 복사, 아니면 쌍선형 크기 변경). 결과 크기가 2 의 거듭제곱이 아니면 원본 크기와 같은 경우뿐이다.
	 */
	public static BufferedImage fit(BufferedImage png, int ow, int oh) {
		int pw = png.getWidth(), ph = png.getHeight();
		if (pw == ow && ph == oh) {
			return argb(png);
		}
		int padW = pow2(ow), padH = pow2(oh);
		int tw = pow2(Math.max(1, Math.round(padW * (float) pw / ow)));
		int th = pow2(Math.max(1, Math.round(padH * (float) ph / oh)));
		int rw = Math.max(1, Math.round(tw * (float) ow / padW)), rh = Math.max(1, Math.round(th * (float) oh / padH));
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		if (rw == pw && rh == ph) {
			out.setRGB(0, 0, pw, ph, png.getRGB(0, 0, pw, ph, null, 0, pw), 0, pw);
		} else {
			Graphics2D g = out.createGraphics();
			g.setComposite(AlphaComposite.Src);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(argb(png), 0, 0, rw, rh, null);
			g.dispose();
		}
		return out;
	}

	/** 그림 전체 ARGB (행 우선). */
	public static int[] pixels(BufferedImage img) {
		return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
	}

	private static BufferedImage argb(BufferedImage img) {
		if (img.getType() == BufferedImage.TYPE_INT_ARGB) {
			return img;
		}
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, img.getWidth(), img.getHeight(), pixels(img), 0, img.getWidth());
		return out;
	}

	private static int pow2(int v) {
		return Integer.bitCount(v) == 1 ? v : Integer.highestOneBit(v) << 1;
	}
}
