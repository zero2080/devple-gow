package devple.gow.tools.anim;

import devple.gow.tools.level.BallHull;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.PreviewRenderer;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.wad.WadFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 스켈레톤/애니메이션 도구.
 * <pre>
 *   anim:info  &lt;wad&gt;                                  스켈레톤 검증 + ANM 세트 요약
 *   anim:skel  &lt;wad&gt; &lt;cxt&gt;                            관절 트리
 *   anim:list  &lt;wad&gt; [ANM_x]                          애니메이션 목록 (길이, 프레임, 트랙 수)
 *   anim:pose  &lt;wad&gt; &lt;MDL_x&gt; &lt;anim|bind|idle&gt; &lt;t&gt; &lt;out.png&gt;
 *   anim:sheet &lt;wad&gt; &lt;MDL_x&gt; &lt;anim&gt; &lt;out.png&gt; [--frames N]
 * 공통 옵션: --skel CXT이름  --anm ANM이름  --ext 다른.wad (외부 그룹 0x10003 연결)  --lod N  --inst N  --yaw 도
 * sheet 옵션: --weapon R_WPN0_n.WAD (블레이드를 lWeapOH/rWeapOH 에 부착)  --hit (CDV 구: 빨강 = 무기/공격, 초록 = 몸)
 * </pre>
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		List<String> a = new ArrayList<>(Arrays.asList(args));
		String skelName = opt(a, "--skel", null);
		String anmName = opt(a, "--anm", null);
		List<String> ext = new ArrayList<>();
		for (String e; (e = opt(a, "--ext", null)) != null; ) {
			ext.add(e);
		}
		int lod = Integer.parseInt(opt(a, "--lod", "0"));
		int inst = Integer.parseInt(opt(a, "--inst", "0"));
		int frames = Integer.parseInt(opt(a, "--frames", "8"));
		double yaw = Double.parseDouble(opt(a, "--yaw", "30"));
		String weapon = opt(a, "--weapon", null);
		boolean hit = a.remove("--hit");
		if (a.size() < 2) {
			System.err.println("usage: anim:<info|skel|list|pose|sheet> <wad> ...");
			System.exit(1);
		}
		WadFile wad = a.get(0).equals("batch") ? null : WadFile.load(Path.of(a.get(1)));
		switch (a.get(0)) {
			case "info" -> info(wad);
			case "skel" -> skel(Skeleton.loadAll(wad).stream().filter(s -> s.name.equals(a.get(2))).findFirst().orElseThrow());
			case "list" -> {
				for (AnimationSet s : AnimationSet.loadAll(wad)) {
					if (a.size() > 2 && !s.name.equals(a.get(2))) {
						continue;
					}
					attach(s, ext);
					list(s);
				}
			}
			case "pose", "sheet" -> {
				Rig r = rig(wad, a.get(2), skelName, anmName, ext, lod, inst);
				if (a.get(0).equals("pose")) {
					pose(r, a.get(3), Float.parseFloat(a.get(4)), Path.of(a.get(5)), yaw);
				} else {
					WeaponAttach wa = weapon != null ? WeaponAttach.blades(WadFile.load(Path.of(weapon))) : null;
					BallHull body = null;
					if (hit) {
						for (String ref : Skeleton.groupRefs(wad, r.skel.name)) {
							for (var t : wad.resources()) {
								if (ref.startsWith("CDV_") && t.name().equals(ref) && t.data() != null && BallHull.is(t.data())) {
									body = new BallHull(t.name(), t.data());
								}
							}
						}
					}
					sheet(r, a.get(3), frames, Path.of(a.get(4)), yaw, wa, hit ? body : null, hit);
				}
			}
			case "batch" -> batch(Path.of(a.get(1)));
			default -> throw new IllegalArgumentException("unknown command: " + a.get(0));
		}
	}

	private static String opt(List<String> a, String key, String def) {
		int i = a.indexOf(key);
		if (i < 0) {
			return def;
		}
		String v = a.get(i + 1);
		a.remove(i + 1);
		a.remove(i);
		return v;
	}

	private static void attach(AnimationSet s, List<String> ext) throws Exception {
		for (String e : ext) {
			s.attachAll(WadFile.load(Path.of(e)));
		}
	}

	private static void info(WadFile wad) {
		for (Skeleton s : Skeleton.loadAll(wad)) {
			float[] v = s.verify();
			System.out.printf("SKEL %-20s joints=%3d inv=%3d ext=%d flags=%x  |idle-bindLocal|=%.4f |W·inv-I|=%.4f refs=%s%n",
					s.name, s.size(), s.inverseBind.length, s.ext.length, s.objectFlags, v[0], v[1], Skeleton.groupRefs(wad, s.name));
		}
		for (AnimationSet s : AnimationSet.loadAll(wad)) {
			int acts = s.all().size();
			long skel = s.all().stream().filter(Animation::hasSkeletalData).count();
			System.out.printf("ANM  %-20s flags=%x types=%s groups=%d acts=%d/%d skeletal=%d%n", s.name, s.flags,
					Arrays.toString(s.typeIds), s.groups.size(), acts, s.totalActs, skel);
			for (AnimationSet.Group g : s.groups) {
				System.out.printf("     group %2d %-12s flags=%x acts=%d%s%n", g.index, g.name, g.flags, g.acts.size(),
						g.external() ? " (외부" + (g.loadedFrom != null ? ": " + g.loadedFrom : ", 미연결") + ")" : "");
			}
		}
	}

	private static void skel(Skeleton s) {
		for (Skeleton.Joint j : s.joints) {
			int depth = 0;
			for (int p = j.parent(); p >= 0; p = s.joints.get(p).parent()) {
				depth++;
			}
			float[] w = s.bindWorld()[j.index()];
			System.out.printf("%3d %s%-20s flags=%05x %s%s inv=%3d world=(%.2f %.2f %.2f) rot=%s%n", j.index(), "  ".repeat(depth), j.name(),
					j.flags(), j.quaternion() ? "Q" : "E", (j.flags() & Skeleton.F_EXT_ROT) != 0 ? "x" + j.extIndex() : "  ", j.invIndex(),
					w[12], w[13], w[14], Arrays.toString(s.idleRot[j.index()]));
		}
	}

	private static void list(AnimationSet s) {
		System.out.printf("%s  types=%s%n", s.name, Arrays.toString(s.typeIds));
		for (AnimationSet.Group g : s.groups) {
			for (Animation an : g.acts) {
				System.out.printf("  %-10s %-24s dur=%6.3fs frames=%4d ft=1/%.0f rotTracks=%3d posTracks=%3d joints<%d flags=%x%s idx=%d blend=%d/%.2f%n",
						g.name, an.name, an.duration, an.frameCount(), 1 / an.frameTime, an.rotTracks.size(), an.posTracks.size(),
						an.jointSpan(), an.flags, an.looping() ? "(loop)" : "", an.globalIndex, an.blendIndex, an.blendParam);
			}
		}
	}

	/**
	 * 전체 WAD 검증: 스켈레톤 검증 오차, ANM 파싱, 스켈레톤과 묶인 모든 act 의 bake + 샘플(NaN, 관절 범위),
	 * 외부 그룹(0x10003) 연결 수, 스키닝 모델의 범위 밖 관절 참조.
	 */
	private static void batch(Path dir) throws Exception {
		List<Path> wads;
		try (var s = java.nio.file.Files.list(dir)) {
			wads = s.filter(p -> p.getFileName().toString().toUpperCase().endsWith(".WAD")).sorted().toList();
		}
		int skels = 0, badSkel = 0, sets = 0, acts = 0, baked = 0, nan = 0, span = 0, ext = 0, extAttached = 0, fail = 0, models = 0, oorModels = 0;
		float worstLocal = 0, worstInv = 0;
		java.util.Map<String, Integer> errors = new java.util.TreeMap<>();
		java.util.Map<String, Integer> locators = new java.util.TreeMap<>();
		for (Path p : wads) {
			WadFile w = WadFile.load(p);
			List<Skeleton> sks;
			try {
				sks = Skeleton.loadAll(w);
			} catch (RuntimeException e) {
				errors.merge("skel: " + e.getMessage(), 1, Integer::sum);
				continue;
			}
			for (var t : w.resources()) {
				if (t.magic() == AnimationSet.MAGIC_EXTERNAL) {
					ext++;
				}
			}
			List<AnimationSet> as = AnimationSet.loadAll(w);
			sets += as.size();
			for (AnimationSet s : as) {
				for (AnimationSet.Group g : s.groups) {
					if (g.loadedFrom != null) {
						extAttached++;
					}
				}
			}
			for (Skeleton sk : sks) {
				skels++;
				float[] v = sk.verify();
				worstLocal = Math.max(worstLocal, v[0]);
				worstInv = Math.max(worstInv, v[1]);
				if (v[0] > 0.05f || v[1] > 0.05f) {
					badSkel++;
					errors.merge(String.format("skel 오차 %s/%s local=%.3f inv=%.3f", p.getFileName(), sk.name, v[0], v[1]), 1, Integer::sum);
				}
				List<String> refs = Skeleton.groupRefs(w, sk.name);
				for (AnimationSet s : as) {
					if (!refs.contains(s.name)) {
						continue;
					}
					for (Animation an : s.all()) {
						acts++;
						try {
							if (an.jointSpan() > sk.size()) {
								span++;
							}
							an.bake(sk);
							baked++;
							for (AnimTrack ct : an.channelTracks) {
								if (an.locatorTrack(sk, ct)) {
									for (int st : ct.streams()) {
										locators.merge(sk.joints.get(st / 4).name(), 1, Integer::sum);
									}
								}
							}
							if (!an.posTracks.isEmpty() && !an.rotTracks.isEmpty() && Math.abs(an.posFrameTime - an.frameTime) > 1e-6f) {
								errors.merge("회전/위치 frameTime 다름", 1, Integer::sum);
							}
							Pose ps = an.sample(sk, an.duration * 0.5f, false);
							for (float[] m : ps.world(sk)) {
								for (float f : m) {
									if (!Float.isFinite(f)) {
										nan++;
										throw new IllegalStateException("NaN");
									}
								}
							}
						} catch (RuntimeException e) {
							fail++;
							errors.merge("act: " + e, 1, Integer::sum);
						}
					}
				}
				for (String r : refs) {
					if (!r.startsWith("MDL_")) {
						continue;
					}
					for (GowModel m : GowModel.load(w)) {
						if (m.name.equals(r)) {
							models++;
							SkinnedModel sm = new SkinnedModel(m, 0, 0);
							if (sm.outOfRange(sk.size())[0] > 0) {
								oorModels++;
								errors.merge("범위 밖 관절 사용: " + p.getFileName() + "/" + m.name, 1, Integer::sum);
							}
						}
					}
				}
			}
		}
		System.out.printf("wads=%d skeletons=%d (오차 초과 %d, 최대 local=%.4f inv=%.4f) anmSets=%d external=%d/%d acts=%d baked=%d fail=%d nan=%d spanOver=%d models=%d oorModels=%d%n",
				wads.size(), skels, badSkel, worstLocal, worstInv, sets, extAttached, ext, acts, baked, fail, nan, span, models, oorModels);
		errors.forEach((k, v) -> System.out.println("  " + v + " × " + k));
		System.out.println("타입 10 로케이터 위치 스트림 (관절 이름별): " + locators);
	}

	/** 모델 + 스켈레톤 + 애니메이션 세트 묶음. */
	record Rig(Skeleton skel, AnimationSet anims, SkinnedModel mesh) {
	}

	static Rig rig(WadFile wad, String mdl, String skelName, String anmName, List<String> ext, int lod, int inst) throws Exception {
		GowModel m = GowModel.load(wad).stream().filter(x -> x.name.equals(mdl)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("모델 없음: " + mdl));
		Skeleton sk = null;
		for (Skeleton s : Skeleton.loadAll(wad)) {
			if (skelName != null ? s.name.equals(skelName) : Skeleton.groupRefs(wad, s.name).contains(mdl)) {
				sk = s;
				break;
			}
		}
		if (sk == null) {
			throw new IllegalArgumentException("스켈레톤(CXT) 없음 — --skel 지정 필요");
		}
		AnimationSet as = null;
		String an = anmName;
		if (an == null) {
			an = Skeleton.groupRefs(wad, sk.name).stream().filter(x -> x.startsWith("ANM_")).findFirst().orElse(null);
		}
		if (an != null) {
			try {
				as = AnimationSet.load(wad, an);
			} catch (IllegalArgumentException e) {
				for (String x : ext) {
					try {
						as = AnimationSet.load(WadFile.load(Path.of(x)), an);
						break;
					} catch (IllegalArgumentException ignored) {
						// 다음 WAD
					}
				}
			}
			if (as != null) {
				attach(as, ext);
			}
		}
		SkinnedModel sm = new SkinnedModel(m, lod, inst);
		int[] oor = sm.outOfRange(sk.size());
		System.out.printf("model=%s skel=%s(%d) anm=%s verts=%d blocks=%d stitch=%d warnings=%d outOfRangeJoint=%d(weighted %d)%n", m.name,
				sk.name, sk.size(), as == null ? "-" : as.name, sm.vertexCount(), sm.blocks, sm.stitchBlocks, sm.warnings.size(), oor[1], oor[0]);
		sm.warnings.stream().distinct().limit(5).forEach(w -> System.out.println("  WARN " + w));
		if (Boolean.getBoolean("anim.debug")) {
			for (SkinnedModel.Part p : sm.parts) {
				java.util.Map<String, Integer> pairs = new java.util.TreeMap<>();
				float[] bb = {1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f};
				for (int i = 0; i < p.pos.size(); i++) {
					int[] j = p.joints.get(i);
					pairs.merge(j[0] + "/" + j[1] + (p.weights.get(i) > 0 ? "w" : ""), 1, Integer::sum);
					for (int k = 0; k < 3; k++) {
						bb[k] = Math.min(bb[k], p.pos.get(i)[k]);
						bb[k + 3] = Math.max(bb[k + 3], p.pos.get(i)[k]);
					}
				}
				System.out.printf("  part %s verts=%d bounds=%s tags=%s pairs=%s%n", p.name, p.pos.size(), Arrays.toString(bb), p.tagKinds, pairs);
			}
		}
		return new Rig(sk, as, sm);
	}

	/** "bind" = 정점 원본(null), "idle" = 아이들 포즈, 그 외 = 애니메이션 이름. */
	static Pose poseFor(Rig r, String anim, float t) {
		if (anim.equals("bind")) {
			return null;
		}
		if (anim.equals("idle")) {
			return Pose.idle(r.skel);
		}
		Animation an = r.anims == null ? null : r.anims.find(anim);
		if (an == null) {
			throw new IllegalArgumentException("애니메이션 없음: " + anim);
		}
		return an.sample(r.skel, t, false);
	}

	private static void pose(Rig r, String anim, float t, Path png, double yaw) throws Exception {
		TriMesh tm = r.mesh.posed(r.skel, poseFor(r, anim, t));
		PreviewRenderer.render(tm, png, 512);
		System.out.printf("%s t=%.3f bounds=%s -> %s%n", anim, t, Arrays.toString(tm.bounds()), png);
	}

	/** 두 줄: 위 = 3/4 시점, 아래 = 측면. 프레임 전체에 같은 프레이밍 사용 (이동이 보이도록). */
	/** 포즈된 메시 + (선택) 부착 블레이드 + (선택) CDV 구 링. */
	static TriMesh compose(Rig r, Pose pose, WeaponAttach wa, BallHull body, boolean hit) {
		TriMesh tm = r.mesh.posed(r.skel, pose);
		float[][] w = pose.world(r.skel);
		List<float[]> bodyBalls = new ArrayList<>();
		List<float[]> weaponBalls = new ArrayList<>();
		if (body != null) {
			for (int b = 0; b < body.balls.size(); b++) {
				int j = body.ballJoint[b];
				if (j < w.length) {
					(body.ballPart(b) == 1 ? bodyBalls : weaponBalls).add(WeaponAttach.posedBall(body, b, w[j]));
				}
			}
		}
		if (wa != null) {
			for (Skeleton.Joint jt : r.skel.joints) {
				if (!jt.name().endsWith("WeapOH")) {
					continue;
				}
				if (wa.model != null) {
					tm.subs.addAll(wa.posed(w[jt.index()]).subs);
				}
				if (hit && wa.cdv != null) {
					float[][] bw = wa.world(w[jt.index()]);
					for (int b = 0; b < wa.cdv.balls.size(); b++) {
						weaponBalls.add(WeaponAttach.posedBall(wa.cdv, b, bw[Math.min(wa.cdv.ballJoint[b], bw.length - 1)]));
					}
				}
			}
		}
		if (hit) {
			tm.subs.add(WeaponAttach.rings(bodyBalls, 0x40E040));
			tm.subs.add(WeaponAttach.rings(weaponBalls, 0xFF3030));
		}
		return tm;
	}

	private static void sheet(Rig r, String anim, int n, Path png, double yaw, WeaponAttach wa, BallHull body, boolean hit)
			throws Exception {
		Animation an = r.anims == null ? null : r.anims.find(anim);
		if (an == null) {
			throw new IllegalArgumentException("애니메이션 없음: " + anim);
		}
		float len = (an.frameCount() - 1) * an.frameTime;
		List<TriMesh> meshes = new ArrayList<>();
		float[] bb = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = 0; i < n; i++) {
			float t = n == 1 ? 0 : len * i / (n - 1);
			TriMesh tm = compose(r, an.sample(r.skel, t, false), wa, body, hit);
			meshes.add(tm);
			float[] b = tm.bounds();
			for (int k = 0; k < 3; k++) {
				bb[k] = Math.min(bb[k], b[k]);
				bb[k + 3] = Math.max(bb[k + 3], b[k + 3]);
			}
		}
		float ext = Math.max(bb[3] - bb[0], Math.max(bb[4] - bb[1], bb[5] - bb[2])) * 1.05f;
		float cx = (bb[0] + bb[3]) / 2, cy = (bb[1] + bb[4]) / 2, cz = (bb[2] + bb[5]) / 2;
		int size = 256;
		BufferedImage img = new BufferedImage(size * n, size * 2 + 16, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < n; i++) {
			PreviewRenderer.drawView(meshes.get(i), img, i * size, 16, size, cx, cy, cz, ext, Math.toRadians(yaw), Math.toRadians(-15));
			PreviewRenderer.drawView(meshes.get(i), img, i * size, 16 + size, size, cx, cy, cz, ext, Math.PI / 2, 0);
		}
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		for (int i = 0; i < n; i++) {
			float t = n == 1 ? 0 : len * i / (n - 1);
			g.drawString(String.format("%s t=%.2f", i == 0 ? anim : "", t), i * size + 4, 12);
		}
		g.dispose();
		ImageIO.write(img, "png", png.toFile());
		System.out.printf("%s frames=%d dur=%.3f sheet=%d -> %s%n", anim, an.frameCount(), an.duration, n, png);
	}
}
