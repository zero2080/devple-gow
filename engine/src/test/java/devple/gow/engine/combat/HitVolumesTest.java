package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.SkinnedModel;
import devple.gow.tools.anim.WeaponAttach;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.PreviewRenderer;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 원작 CDV 히트박스: 블레이드 부착 구(부위 2/3/11) × 미노타우로스 몸 구 (34 문서). */
class HitVolumesTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")) && Files.exists(rom.resolve("R_WPN0_2.WAD")), "gow-rom 없음");
		return rom;
	}

	@Test
	void cdvPartTypes() throws Exception {
		Path rom = rom();
		WadFile hero = WadFile.load(rom.resolve("R_HERO0.WAD"));
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		BallHull h = HitVolumes.cdvFor(hero, sk);
		assertArrayEquals(new int[]{4, 56, 60, 1, 1}, h.ballJoint, "CDV_hero 관절 (vertebrae4, 발 2, pelvis 2)");
		assertArrayEquals(new int[]{1, 7, 9}, h.materialPart, "Body 1 / rightFoot 7 / extra1 9");
		WeaponAttach w = WeaponAttach.blades(WadFile.load(rom.resolve("R_WPN0_2.WAD")));
		assertArrayEquals(new int[]{2, 11}, w.cdv.materialPart, "weaponLeftMat 2 / weaponTwinCollisionMat 11");
		HitVolumes v = HitVolumes.forHero(hero, sk, WadFile.load(rom.resolve("R_WPN0_2.WAD")));
		assertTrue(v.hasPart(2) && v.hasPart(3) && v.hasPart(11) && v.hasPart(9) && v.hasPart(1));
	}

	@Test
	void sweptSpheres() {
		// 빠르게 지나가는 구: 시작/끝 모두 멀지만 중간에 관통
		assertTrue(HitVolumes.swept(new float[]{-100, 0, 0, 5}, new float[]{100, 0, 0, 5}, new float[]{0, 3, 0, 5}, new float[]{0, 3, 0, 5}));
		assertFalse(HitVolumes.swept(new float[]{-100, 20, 0, 5}, new float[]{100, 20, 0, 5}, new float[]{0, 0, 0, 5}, new float[]{0, 0, 0, 5}));
	}

	/** combo3A(왼쪽 블레이드 투척, 부위 2): 정면 40 의 미노타우로스는 맞고 등 뒤 40 은 안 맞는다. */
	@Test
	void combo3AHitsFrontNotBehind() throws Exception {
		assertEquals(1, swing(-40, "front").hits, "정면 40");
		assertEquals(0, swing(40, "behind").hits, "등 뒤 40");
	}

	private record Result(int hits) {
	}

	private Result swing(float z, String tag) throws Exception {
		Path rom = rom();
		WadFile heroWad = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		WadFile wpn = WadFile.load(rom.resolve("R_WPN0_2.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		Animator animator = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(animator);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 2; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), animator, owner);
		c.setHitVolumes(HitVolumes.forHero(heroWad, sk, wpn));
		EnemyFactory.Kind kind = EnemyFactory.load(rom.resolve("R_MIN.WAD"), perm, -1);
		EnemyFighter min = kind.spawn();
		min.pos.set(0, 0, z);
		min.yaw = z < 0 ? 0 : (float) Math.PI; // 영웅 쪽을 봄 (정면 -Z 규약)
		c.setTarget(min);
		int[] hits = {0};
		c.onLog(s -> {
			if (s.contains("적중")) {
				hits[0]++;
			}
		});
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.05f, PadButton.SQUARE)));
		float dt = 1 / 60f;
		List<TriMesh> frames = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		Path out = rom.toAbsolutePath().getParent().resolve("work/hitbox");
		boolean draw = Files.isDirectory(out);
		int step = 0;
		for (float t = 0; t < 1.6f; t += dt, step++) {
			in.advance(dt);
			c.update(in, dt);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, null);
			} else {
				ctl.update(in, 0, dt);
			}
			min.update(dt);
			if (draw && c.active() && step % 6 == 0 && frames.size() < 8) {
				frames.add(scene(rom, heroWad, sk, wpn, animator, ctl, c, kind, min));
				labels.add(String.format("%s t=%.2f p=%.2f", tag, t, c.progress()));
			}
		}
		if (draw) {
			sheet(frames, labels, out.resolve("combo3A_" + tag + ".png"));
		}
		return new Result(hits[0]);
	}

	// ---- 디버그 이미지 (work/hitbox 가 있을 때만) ----

	private static TriMesh scene(Path rom, WadFile heroWad, Skeleton sk, WadFile wpn, Animator animator, HeroController ctl,
								 Combatant c, EnemyFactory.Kind kind, EnemyFighter min) {
		TriMesh tm = new TriMesh("scene");
		float[][] w = animator.pose().world(sk);
		GowModel hm = GowModel.load(heroWad).stream().filter(m -> m.name.equals("MDL_hero")).findFirst().orElseThrow();
		addWorld(tm, new SkinnedModel(hm, 0, 0).posed(w, sk.skin(w)), ctl.position, ctl.yaw);
		WeaponAttach blades = WeaponAttach.blades(wpn);
		for (String j : new String[]{"lWeapOH", "rWeapOH"}) {
			addWorld(tm, blades.posed(w[sk.indexOf(j)]), ctl.position, ctl.yaw);
		}
		Skeleton ms = kind.skeleton();
		float[][] mw = min.animator().pose().world(ms);
		GowModel mm = GowModel.load(kind.assets().wad()).stream().filter(m -> m.name.equals(kind.model())).findFirst().orElseThrow();
		addWorld(tm, new SkinnedModel(mm, 0, 0).posed(mw, ms.skin(mw)), min.pos, min.yaw);
		tm.subs.add(WeaponAttach.rings(c.hitVolumes().spheres(2), 0xFF3030));
		tm.subs.add(WeaponAttach.rings(c.hitVolumes().spheres(3), 0xFF9020));
		tm.subs.add(WeaponAttach.rings(min.hitVolumes().spheres(HitVolumes.PART_BODY), 0x40E040));
		return tm;
	}

	private static void addWorld(TriMesh dst, TriMesh src, Vector3f pos, float yaw) {
		Matrix4f m = new Matrix4f().translation(pos).rotateY(yaw);
		Vector3f v = new Vector3f();
		for (TriMesh.Sub s : src.subs) {
			for (float[] p : s.pos) {
				m.transformPosition(v.set(p[0], p[1], p[2]));
				p[0] = v.x;
				p[1] = v.y;
				p[2] = v.z;
			}
			dst.subs.add(s);
		}
	}

	private static void sheet(List<TriMesh> frames, List<String> labels, Path png) throws Exception {
		int size = 320, n = Math.max(1, frames.size());
		BufferedImage img = new BufferedImage(size * n, size * 2 + 16, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < frames.size(); i++) {
			// 위 = 위에서 내려다봄(정면 -Z 가 화면 위), 아래 = 측면
			PreviewRenderer.drawView(frames.get(i), img, i * size, 16, size, 0, 20, 0, 180, 0, Math.toRadians(-89));
			PreviewRenderer.drawView(frames.get(i), img, i * size, 16 + size, size, 0, 20, 0, 180, Math.PI / 2, 0);
		}
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		for (int i = 0; i < labels.size(); i++) {
			g.drawString(labels.get(i), i * size + 4, 12);
		}
		g.dispose();
		ImageIO.write(img, "png", png.toFile());
	}
}
