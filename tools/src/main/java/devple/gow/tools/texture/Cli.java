package devple.gow.tools.texture;

import devple.gow.tools.wad.WadFile;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 텍스처 도구.
 * <pre>
 *   texture:export <wad> <outDir>   TXR 전부 → PNG (TXR 이름, 중복 시 _2, 추가 CLUT 은 _clutN)
 *   texture:info   <wad>            텍스처 표 (크기/포맷/팔레트/밉)
 *   texture:mat    <wad>            머티리얼 레이어 표
 *   texture:check  <dir>            디렉터리의 모든 WAD 파싱+디코드 검사 (실패 수 집계)
 *   texture:mipcheck <dir>          밉 체인으로 PSMT8 스위즐 여부 판별 통계
 * </pre>
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "export" -> export(Path.of(args[1]), Path.of(args[2]), List.of(args).contains("--preview"));
			case "info" -> info(Path.of(args[1]));
			case "mat" -> mat(Path.of(args[1]));
			case "check" -> check(Path.of(args[1]));
			case "mipcheck" -> mipCheck(Path.of(args[1]));
			default -> throw new IllegalArgumentException("unknown texture command: " + args[0]);
		}
	}

	private static void export(Path wadPath, Path outDir, boolean preview) throws Exception {
		TextureLibrary lib = new TextureLibrary(WadFile.load(wadPath));
		Files.createDirectories(outDir);
		Map<String, Integer> used = new HashMap<>();
		int n = 0;
		for (TextureLibrary.Entry e : lib.textures()) {
			String base = e.txr().name();
			int dup = used.merge(base, 1, Integer::sum);
			if (dup > 1) {
				base += "_" + dup;
			}
			GfxResource g = e.gfx();
			int cluts = e.pal() != null && g.bpp() <= 8 ? TextureDecoder.clutCount(e.pal(), 1 << g.bpp()) : 1;
			for (int c = 0; c < cluts; c++) {
				int[] argb = TextureDecoder.decode(g, e.pal(), c);
				if (preview) {
					checker(argb, g.width());
				}
				String fn = base + (c > 0 ? "_clut" + c : "") + ".png";
				ImageIO.write(TextureDecoder.toImage(g.width(), g.height(), argb), "png", outDir.resolve(fn).toFile());
				n++;
			}
		}
		System.out.printf("%s: %d textures → %d png → %s%n", wadPath.getFileName(), lib.textures().size(), n, outDir);
	}

	/** 확인용: 알파를 8px 체커보드 위에 합성해 불투명 이미지로 만든다. */
	private static void checker(int[] argb, int w) {
		for (int i = 0; i < argb.length; i++) {
			int x = i % w, y = i / w;
			int bg = ((x >> 3) + (y >> 3) & 1) == 0 ? 0x40 : 0x70;
			int a = argb[i] >>> 24;
			int r = ((argb[i] >> 16 & 0xFF) * a + bg * (255 - a)) / 255;
			int g = ((argb[i] >> 8 & 0xFF) * a + bg * (255 - a)) / 255;
			int b = ((argb[i] & 0xFF) * a + bg * (255 - a)) / 255;
			argb[i] = 0xFF000000 | r << 16 | g << 8 | b;
		}
	}

	private static void info(Path wadPath) throws Exception {
		TextureLibrary lib = new TextureLibrary(WadFile.load(wadPath));
		System.out.printf("%-26s %-24s %9s %-6s %3s %-24s %4s %-24s %5s %7s %8s%n",
				"TXR", "GFX", "size", "psm", "frm", "PAL", "cluts", "nextMip", "mipK", "mipL", "flags");
		for (TextureLibrary.Entry e : lib.textures()) {
			GfxResource g = e.gfx();
			TextureResource t = e.txr();
			String pal = e.pal() == null ? (g.bpp() <= 8 ? "(missing " + t.pal() + ")" : "-")
					: e.pal().name() + (e.mipLevel() > 0 ? "^" + e.mipLevel() : "");
			int cluts = e.pal() != null ? TextureDecoder.clutCount(e.pal(), 1 << Math.min(g.bpp(), 8)) : 0;
			System.out.printf("%-26s %-24s %4dx%-4d %-6s %3d %-24s %4d %-24s %5d %7.1f %08x%n",
					t.name(), g.name(), g.width(), g.height(), g.psm(), g.frames(), pal, cluts,
					t.nextMip(), t.mipK(), t.mipL(), t.flags());
		}
		System.out.printf("%d textures%n", lib.textures().size());
	}

	private static void mat(Path wadPath) throws Exception {
		TextureLibrary lib = new TextureLibrary(WadFile.load(wadPath));
		for (MaterialResource m : lib.materials()) {
			System.out.printf("%-26s color=(%.2f %.2f %.2f) p14=%.2f spec=%.1f p30=%d layers=%d%n", m.name(),
					m.color()[0], m.color()[1], m.color()[2], m.param14(), m.specPower(), m.param30(), m.layers().size());
			for (MaterialResource.Layer l : m.layers()) {
				System.out.printf("    %-8s rf=%08x f2=%08x tex=%-24s rgba=(%.2f %.2f %.2f %.2f) p38=%.2f%n",
						l.blendName(), l.renderFlags(), l.flags2(), l.texture(),
						l.rgba()[0], l.rgba()[1], l.rgba()[2], l.rgba()[3], l.param38());
			}
		}
	}

	/**
	 * 스위즐 판별 검증: 밉 체인에서 상위 레벨을 2x2 평균 축소한 이미지와
	 * 하위 레벨을 (선형 / swizzle8) 두 방식으로 디코드한 결과의 오차를 비교해 크기별로 어느 쪽이 맞는지 집계.
	 */
	private static void mipCheck(Path dir) throws Exception {
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.getFileName().toString().endsWith(".WAD")).sorted().toList();
		}
		for (int[] sz : new int[][]{{256, 256}, {128, 128}, {256, 64}, {64, 64}, {128, 64}, {64, 32}, {32, 32}, {32, 8},
				{16, 4}, {8, 32}, {8, 8}}) {
			int w = sz[0], h = sz[1], same = 0;
			int[] map = GsSwizzle.unswizzleMap8(w, h);
			for (int i = 0; i < w * h; i++) {
				if (map[i] == TextureDecoder.swizzle8(i % w, i / w, w)) {
					same++;
				}
			}
			System.out.printf("GS 에뮬 vs 공식 %dx%d: %d/%d 일치%n", w, h, same, w * h);
		}
		Map<String, int[]> stats = new java.util.TreeMap<>();
		for (Path p : wads) {
			TextureLibrary lib = new TextureLibrary(WadFile.load(p));
			Map<String, TextureLibrary.Entry> byName = new HashMap<>();
			for (TextureLibrary.Entry e : lib.textures()) {
				byName.putIfAbsent(e.txr().name(), e);
			}
			for (TextureLibrary.Entry e : lib.textures()) {
				TextureLibrary.Entry c = byName.get(e.txr().nextMip());
				if (c == null || e.pal() == null || e.gfx().bpp() != 8 || c.gfx().bpp() != 8) {
					continue;
				}
				GfxResource pg = e.gfx(), cg = c.gfx();
				if (cg.width() * 2 != pg.width() || (cg.flags() & 2) != 0) {
					continue;
				}
				int[] par = TextureDecoder.decode(pg, e.pal(), 0);
				double lin = mipError(par, pg.width(), TextureDecoder.decode(cg, e.pal(), 0, false), cg);
				double swz = mipError(par, pg.width(), TextureDecoder.decode(cg, e.pal(), 0, true), cg);
				int[] st = stats.computeIfAbsent(String.format("%3dx%-3d flags=%d", cg.width(), cg.height(), cg.flags()),
						k -> new int[3]);
				st[lin < swz ? 0 : lin > swz ? 1 : 2]++;
				if (cg.width() < 16 || cg.height() < 4) {
					int w2 = 16, h2 = cg.width() * cg.height() / 16;
					int[] lut = TextureDecoder.clut(e.pal(), 0, 256);
					int[] img = new int[w2 * h2];
					for (int i = 0; i < img.length; i++) {
						int s = TextureDecoder.swizzle8(i % w2, i / w2, w2);
						img[i] = lut[s < cg.data().limit() ? cg.data().get(s) & 0xFF : 0];
					}
					System.out.printf("  %s %s %dx%d 평균오차 linear=%.1f swizzle8=%.1f reshape16=%.1f%n", p.getFileName(),
							c.txr().name(), cg.width(), cg.height(), lin, swz, mipError(par, pg.width(), img, cg));
				}
			}
		}
		stats.forEach((k, v) -> System.out.printf("%s  linear %4d  swizzle8 %4d  동점 %d%n", k, v[0], v[1], v[2]));
	}

	private static double mipError(int[] par, int pw, int[] child, GfxResource cg) {
		double err = 0;
		int n = 0;
		for (int y = 0; y < cg.height() && 2 * y + 1 < par.length / pw; y++) {
			for (int x = 0; x < cg.width(); x++) {
				for (int sh = 0; sh <= 16; sh += 8) {
					int sum = 0;
					for (int k = 0; k < 4; k++) {
						sum += par[(2 * y + (k >> 1)) * pw + 2 * x + (k & 1)] >> sh & 0xFF;
					}
					err += Math.abs(sum / 4 - (child[y * cg.width() + x] >> sh & 0xFF));
				}
				n++;
			}
		}
		return n == 0 ? 0 : err / n;
	}

	private static void check(Path dir) throws Exception {
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.getFileName().toString().endsWith(".WAD")).sorted().toList();
		}
		int failedWads = 0, tex = 0, mats = 0, missingPal = 0, decodeFail = 0;
		for (Path p : wads) {
			try {
				TextureLibrary lib = new TextureLibrary(WadFile.load(p));
				mats += lib.materials().size();
				for (TextureLibrary.Entry e : lib.textures()) {
					tex++;
					if (e.pal() == null && e.gfx().bpp() <= 8) {
						missingPal++;
					}
					try {
						TextureDecoder.decode(e.gfx(), e.pal(), 0);
					} catch (RuntimeException ex) {
						decodeFail++;
						System.out.printf("  decode fail %s/%s: %s%n", p.getFileName(), e.txr().name(), ex.getMessage());
					}
				}
			} catch (RuntimeException ex) {
				failedWads++;
				System.out.printf("FAIL %s: %s%n", p.getFileName(), ex);
			}
		}
		System.out.printf("WAD %d (실패 %d), 텍스처 %d (팔레트 없음 %d, 디코드 실패 %d), 머티리얼 %d%n",
				wads.size(), failedWads, tex, missingPal, decodeFail, mats);
	}
}
