package devple.gow.tools.fx;

import devple.gow.tools.wad.WadFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 환경 / 이펙트 도구 (16 문서).
 * <pre>
 *   fx:env    &lt;wad&gt;        환경 스크립트 (Fog / LayeredFog / FarClip / Sky / Weather / Water / Mirror) 해석 값
 *   fx:survey &lt;dir&gt;        전 WAD 환경 스크립트 target 별 개수와 레벨 목록
 *   fx:weather &lt;dir&gt;       전 WAD SCR_Weather 값 + 그 WAD 의 날씨 PTC_ (rain/snow/plankton/dust/ember/fog) VU 요약
 *   fx:ptc    &lt;wad&gt; [필터]  FXC_ 이미터 / 필드 / PTC_ 모양과 연결 (모양 이름, 머티리얼)
 *   fx:matanim &lt;wad&gt;        머티리얼 ANM (플립북 프레임 / UV 스크롤 / 색·회전 트랙)
 *   fx:parts  &lt;wad&gt; &lt;go&gt;   배치된 인스턴스의 서브메시별 머티리얼 / AABB (하늘 돔 구조 확인)
 * </pre>
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: fx:<env|survey> <wad|dir>");
			System.exit(1);
		}
		switch (args[0]) {
			case "env" -> System.out.print(new EnvParams(WadFile.load(Path.of(args[1]))).describe());
			case "survey" -> survey(Path.of(args[1]));
			case "weather" -> weatherSurvey(Path.of(args[1]));
 			case "ptc" -> ptc(Path.of(args[1]), args.length > 2 ? args[2].toLowerCase() : "");
			case "ptcsurvey" -> ptcSurvey(Path.of(args[1]));
			case "ptcvu" -> ptcvu(Path.of(args[1]), args.length > 2 ? args[2].toLowerCase() : "");
			case "matanim" -> {
				for (MaterialAnim.Anim a : MaterialAnim.load(WadFile.load(Path.of(args[1]))).values()) {
					StringBuilder b = new StringBuilder();
					a.uv().forEach((l, tr) -> b.append(String.format(java.util.Locale.ROOT, " uv%d u(%.2f→%.2f) v(%.2f→%.2f)", l, tr[0].values()[0],
							tr[0].values()[tr[0].values().length - 1], tr[1].values()[0], tr[1].values()[tr[1].values().length - 1])));
					a.floats().forEach((k, tr) -> b.append(String.format(java.util.Locale.ROOT, " %s(%.2f→%.2f)", k, tr.values()[0],
							tr.values()[tr.values().length - 1])));
					System.out.printf(java.util.Locale.ROOT, "%-28s %-24s dur=%.2f loop=%b frames=%s%s%n", a.material(), a.anm(), a.duration(), a.loop(),
							a.frames() == null ? "-" : a.frames().length + "키", b);
				}
			}
			case "parts" -> parts(Path.of(args[1]), args[2]);
			default -> throw new IllegalArgumentException(args[0]);
		}
	}

	private static void ptc(Path wadPath, String filter) throws Exception {
		FxResources r = new FxResources(WadFile.load(wadPath));
		for (FxResources.Emitter e : r.emitters.values()) {
			if (!e.name().toLowerCase().contains(filter)) {
				continue;
			}
			FxResources.Shape s = r.byShape.get(e.shape());
			System.out.printf(java.util.Locale.ROOT, "%-22s sub=%d hdr=(%d,%d) pos=(%.2f,%.2f,%.2f) dir=%s spread=%.3f speed=%.3f±%.3f dist=%.2f..%.2f rate=%.1f lod=%.0f/%.0f%n"
							+ "    → %s%n", e.name(), e.subtype(), e.header()[0], e.header()[1], e.local()[12], e.local()[13], e.local()[14],
					EnvParams.v(e.dir()), e.spread(), e.speed(), e.speedRnd(), e.minDist(), e.maxDist(), e.rate(), e.lodNear(), e.lodFar(),
					s == null ? "(모양 없음 " + e.shape() + ")" : String.format(java.util.Locale.ROOT,
							"%s count=%d life=%.2f size=%.1f lod=%.0f/%.0f flags=%x mode=%d vu=%d mat=%s", s.name(), s.maxCount(), s.lifespan(),
							s.size(), s.lodNear(), s.lodFar(), s.flags(), s.renderMode(), s.vuType(), s.material())
							+ (s.colorCurve() == null ? "" : " curve=" + EnvParams.v(s.colorCurve())));
		}
		for (FxResources.Field f : r.fields.values()) {
			if (f.name().toLowerCase().contains(filter)) {
				System.out.printf(java.util.Locale.ROOT, "%-22s field kind=%d mag=%.3f dir=%s att=%.2f dist=%.1f..%.1f%n", f.name(), f.kind(),
						f.magnitude(), EnvParams.v(f.dir()), f.attenuation(), f.minDist(), f.maxDist());
			}
		}
	}

	/** 전 WAD PTC VU 블록 서명 (종류, 레코드 배치, 플래그&amp;3, 연산, 출력) 분포. */
	private static void ptcSurvey(Path dir) throws Exception {
		Map<String, int[]> count = new TreeMap<>();
		Map<String, String> example = new TreeMap<>();
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		for (Path p : wads) {
			for (devple.gow.tools.wad.WadTag t : WadFile.load(p).tags()) {
				if (t.tag() == devple.gow.tools.wad.WadTagType.RESOURCE && t.magic() == 0x13 && t.hasData() && t.size() >= 0x128) {
					java.nio.ByteBuffer d = t.data();
					PtcVu v = new PtcVu(d);
					StringBuilder k = new StringBuilder(String.format(java.util.Locale.ROOT, "kind=%d lay=%08x fl&3=%d fl=%x ops=", v.kind,
							d.getInt(0x8C), d.getInt(0x80) & 3, d.getInt(0x80) & 0x7E7FC));
					k.append(java.util.Arrays.toString(v.tables[6])).append(" t0=").append(java.util.Arrays.toString(v.tables[0]))
							.append(" t1=").append(java.util.Arrays.toString(v.tables[1]))
							.append(" t4=").append(java.util.Arrays.toString(v.tables[4])).append(" t5=").append(java.util.Arrays.toString(v.tables[5]));
					count.computeIfAbsent(k.toString(), x -> new int[1])[0]++;
					example.putIfAbsent(k.toString(), p.getFileName() + ":" + t.name());
				}
			}
		}
		count.forEach((k, c) -> System.out.printf("%5d %s  e.g. %s%n", c[0], k, example.get(k)));
	}

	/** 전 WAD SCR_Weather 와 같은 WAD 의 날씨 PTC_ (16 문서 §1.5). */
	private static void weatherSurvey(Path dir) throws Exception {
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		for (Path p : wads) {
			WadFile wad = WadFile.load(p);
			EnvParams env = new EnvParams(wad);
			if (env.weathers.isEmpty() && !p.getFileName().toString().equals("R_PERMK.WAD")) {
				continue;   // R_PERMK = 상주 날씨 GO (gosnow, goplankton, godust, goembers, gogroundfog)
			}
			for (EnvParams.Weather w : env.weathers) {
				System.out.printf(java.util.Locale.ROOT, "%-10s %-18s type=%d(%s) vec=%s box=%s%n", p.getFileName(), w.owner(), w.type(),
						w.typeName(), EnvParams.v(w.vec()), EnvParams.v(w.box()));
			}
			FxResources r = new FxResources(wad);
			for (FxResources.Shape s : r.shapes.values()) {
				String n = s.name().toLowerCase();
				if (n.matches("ptc_(rain|snow|plankton|dust|embers|groundfog)")) {
					System.out.printf(java.util.Locale.ROOT, "    %s count=%d life=%.2f flags=%x vu=%d mat=%s%n", s.name(), s.maxCount(), s.lifespan(),
							s.flags(), s.vuType(), s.material());
				}
			}
		}
	}

	/** PTC_ VU 블록 식 목록 (PtcVu). */
	private static void ptcvu(Path wadPath, String filter) throws Exception {
		for (devple.gow.tools.wad.WadTag t : WadFile.load(wadPath).tags()) {
			if (t.tag() == devple.gow.tools.wad.WadTagType.RESOURCE && t.magic() == 0x13 && t.hasData() && t.size() >= 0x128
					&& t.name().toLowerCase().contains(filter)) {
				java.nio.ByteBuffer d = t.data();
				int lay = d.getInt(0x8C), lay2 = d.getInt(0x90), lay3 = d.getInt(0x94);
				System.out.printf(java.util.Locale.ROOT, "%s life=%.2f size=%.1f flags=%x rec=%d layout=%08x %08x %08x%n", t.name(),
						d.getFloat(0x70), d.getFloat(0x74), d.getInt(0x80), d.getInt(0x84), lay, lay2, lay3);
				System.out.print(new PtcVu(d).describe());
			}
		}
	}

	private static void parts(Path wadPath, String go) throws Exception {
		devple.gow.tools.level.LevelData lv = new devple.gow.tools.level.LevelData(WadFile.load(wadPath));
		List<devple.gow.tools.model.GowModel> models = devple.gow.tools.model.GowModel.load(lv.wad);
		for (devple.gow.tools.level.LevelLayout.Placement p : lv.layout.placements()) {
			if (!p.instance().name().equalsIgnoreCase(go) || p.model() == null) {
				continue;
			}
			devple.gow.tools.model.GowModel gm = models.stream().filter(m -> m.name.equals(p.model())).findFirst().orElseThrow();
			devple.gow.tools.model.TriMesh tm = devple.gow.tools.level.PlacedMesh.build(gm, p.object().skeleton(), p.jointWorld(), 0);
			for (devple.gow.tools.model.TriMesh.Sub sub : tm.subs) {
				float[] mn = {1e9f, 1e9f, 1e9f}, mx = {-1e9f, -1e9f, -1e9f};
				for (float[] v : sub.pos) {
					for (int k = 0; k < 3; k++) {
						mn[k] = Math.min(mn[k], v[k]);
						mx[k] = Math.max(mx[k], v[k]);
					}
				}
				System.out.printf(java.util.Locale.ROOT, "%-28s %-28s v=%5d (%.0f,%.0f,%.0f)..(%.0f,%.0f,%.0f)%n", sub.name, sub.material, sub.pos.size(),
						mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
			}
		}
	}

	private static void survey(Path dir) throws Exception {
		Map<String, StringBuilder> by = new TreeMap<>();
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		for (Path p : wads) {
			for (EnvScript e : EnvScript.load(WadFile.load(p))) {
				if (e.instance()) {
					by.computeIfAbsent(e.target(), k -> new StringBuilder()).append(' ').append(p.getFileName().toString().replace(".WAD", ""));
				}
			}
		}
		by.forEach((k, v) -> System.out.printf("%-18s %4d :%s%n", k, v.toString().split(" ").length - 1, v));
	}
}
