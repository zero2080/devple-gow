package devple.gow.tools.level;

import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.wad.WadFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 레벨 구성 도구.
 * <pre>
 *   level:info  &lt;wad&gt;          배치(인스턴스 → 오브젝트 → MDL, 행렬), 충돌 시트, 볼륨, 카메라 요약
 *   level:check &lt;wad&gt;          배치한 모델 AABB 중심과 인스턴스 0x40 경계 중심 비교 (배치 규칙 검증)
 *   level:mats  &lt;wad&gt;          배치 모델별 서브메시 머티리얼 / 블렌드 / 텍스처 유무 / 평균 정점색 (렌더 이상 진단)
 * </pre>
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: level:<info|check> <wad>");
			System.exit(1);
		}
		LevelData lv = new LevelData(WadFile.load(Path.of(args[1])));
		switch (args[0]) {
			case "info" -> info(lv);
			case "check" -> check(lv);
			case "mats" -> mats(lv);
			default -> throw new IllegalArgumentException(args[0]);
		}
	}

	private static String v(float[] a, int n) {
		StringBuilder sb = new StringBuilder("(");
		for (int i = 0; i < n; i++) {
			sb.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.1f", a[i]));
		}
		return sb.append(')').toString();
	}

	private static void info(LevelData lv) {
		LevelLayout l = lv.layout;
		System.out.printf("%s: 오브젝트 %d, 인스턴스 %d, 미배치 오브젝트 %d%n", l.wadName, l.objects.size(), l.instances.size(), l.unplaced.size());
		for (LevelLayout.Placement p : l.placements()) {
			GoInstance g = p.instance();
			float[] w = p.jointWorld()[0];
			System.out.printf("  %-24s id=%3d fl=%d j=%2d pos=%s rot=%s w=%.3f -> j0=%s  %s%n", g.name(), g.id(), g.flags(),
					p.object().skeleton().size(), v(g.pos(), 3), v(g.rot(), 3), g.scale(), v(new float[]{w[12], w[13], w[14]}, 3),
					p.object().refs());
		}
		System.out.println("미배치: " + l.unplaced);
		for (CollisionSheet s : lv.sheets) {
			System.out.printf("시트 %s: 정점 %d, 면 %d, kd 레코드 %d, AABB %s..%s%n", s.name, s.vertices.length, s.polys.size(),
					s.treeCount, v(s.min, 3), v(s.max, 3));
		}
		for (LevelData.PlacedVolume pv : lv.volumes) {
			BallHull h = pv.hull();
			System.out.printf("볼륨 %-24s %-11s owner=%-22s ball=%d hull=%d sphere=%s names=%s%n", h.name, h.kindName(), pv.owner(), h.balls.size(), h.hulls.size(),
					v(h.sphere, 4), h.names);
		}
		for (LevelData.PlacedCamera c : lv.placedCameras) {
			System.out.printf("카메라 %-20s owner=%-20s eye=%s fwd=%s rail=%s mode=%d/%d fov?=%s p=%s%n", c.def().name(), c.owner(),
					v(c.eye(), 3), v(c.forward(), 3), c.def().rail(), c.def().mode(), c.def().railKind(), v(c.def().fov(), 2),
					v(c.def().params(), 4));
		}
	}

	private static void check(LevelData lv) {
		List<GowModel> models = GowModel.load(lv.wad);
		for (LevelLayout.Placement p : lv.layout.placements()) {
			String mdl = p.model();
			if (mdl == null) {
				continue;
			}
			GowModel gm = models.stream().filter(m -> m.name.equals(mdl)).findFirst().orElse(null);
			if (gm == null) {
				System.out.printf("  %-24s %s 없음%n", p.instance().name(), mdl);
				continue;
			}
			Skeleton sk = p.object().skeleton();
			TriMesh tm = PlacedMesh.build(gm, sk, p.jointWorld(), 0);
			float[] b = tm.bounds();
			float[] c = {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
			float[] e = p.instance().bound();
			double d = Math.sqrt(Math.pow(c[0] - e[0], 2) + Math.pow(c[1] - e[1], 2) + Math.pow(c[2] - e[2], 2));
			float[] bl = sk.bindLocal[0];
			System.out.printf("  %-24s %-22s v=%6d 중심=%s 기록=%s 차=%.1f 크기=%s Tj=%s S=%.4f Ti=%s%n", p.instance().name(), mdl, tm.vertexCount(), v(c, 3),
					v(e, 3), d, v(new float[]{b[3] - b[0], b[4] - b[1], b[5] - b[2]}, 3), v(new float[]{bl[12], bl[13], bl[14]}, 3),
					Math.sqrt(bl[0] * bl[0] + bl[1] * bl[1] + bl[2] * bl[2]), v(p.instance().pos(), 3));
		}
	}

	private static void mats(LevelData lv) {
		List<GowModel> models = GowModel.load(lv.wad);
		java.util.Map<String, devple.gow.tools.texture.MaterialResource> mats = new java.util.HashMap<>();
		java.util.Set<String> txrs = new java.util.HashSet<>();
		devple.gow.tools.texture.TextureLibrary lib = new devple.gow.tools.texture.TextureLibrary(lv.wad);
		lib.materials().forEach(m -> mats.putIfAbsent(m.name(), m));
		lib.textures().forEach(t -> txrs.add(t.txr().name()));
		for (LevelLayout.Placement p : lv.layout.placements()) {
			GowModel gm = p.model() == null ? null : models.stream().filter(m -> m.name.equals(p.model())).findFirst().orElse(null);
			if (gm == null) {
				continue;
			}
			TriMesh tm = PlacedMesh.build(gm, p.object().skeleton(), p.jointWorld(), 0);
			for (TriMesh.Sub s : tm.subs) {
				double c = 0;
				for (int[] col : s.color) {
					c += (col[0] + col[1] + col[2]) / 3.0;
				}
				devple.gow.tools.texture.MaterialResource m = mats.get(s.material);
				String layers = m == null ? "MAT 없음" : m.layers().stream()
						.map(l -> l.blendName() + ":" + l.texture() + (l.texture() != null && !txrs.contains(l.texture()) ? "(없음)" : ""))
						.toList().toString();
				System.out.printf(Locale.ROOT, "  %-22s %-26s v=%5d 색=%5.1f %s%n", p.instance().name(), s.material, s.pos.size(),
						s.color.isEmpty() ? 0 : c / s.color.size(), layers);
			}
		}
	}
}
