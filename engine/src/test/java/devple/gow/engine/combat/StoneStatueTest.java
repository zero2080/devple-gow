package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.GoBehavior;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.texture.TextureLibrary;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 석화 겉모습 (35 문서 §2.2): 원작은 석화된 적을 다시 칠하지 않고 Sold +0x20 석상 GO (goMINStonedBRK, 돌 텍스처) 로 바꿔 끼운다.
 * SCR_TurnToRock (0x19F8E0) → 0x19F798 (진행률 &gt; 0.99) → 0x1E8960 석상 생성·소유자 숨김, Brkb 517 타수 7 → 파괴 act, 0x1E8AF8 해제.
 */
class StoneStatueTest {

	private static final float DT = 1 / 60f;

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_MIN.WAD")), "gow-rom 없음");
		return rom;
	}

	@Test
	void soldNamesStatueAndFreezeGos() throws Exception {
		WadFile min = WadFile.load(rom().resolve("R_MIN.WAD"));
		AiData.Stone st = AiData.load(min).stone(50);
		assertNotNull(st);
		assertEquals(6000, st.resist());
		assertEquals(160, st.move());
		assertEquals("goMINStonedBRK", st.statue(), "Sold +0x20 (0x1E8960 이 0x243700 +0x20 으로 생성)");
		assertEquals("goMINFreeze", st.freeze(), "Sold +0x38 (SCR_TurnToRock 0x19F9E8)");
		GoBehavior b = GoBehavior.find(min, "goMINStonedBRK");
		assertNotNull(b);
		assertEquals("Breakable", b.type());
		assertEquals(517, b.template(), "Brkb 517 \"MIN StoneStatue\"");
		Tweaks tw = new Tweaks();
		tw.load(min);
		assertEquals("MIN StoneStatue", tw.s("/TweakTemplates/Brkb/517/", "Instance Name", null));
		assertEquals(7f, tw.f("/TweakTemplates/Brkb/517/", "Hit Points", 0f), 0.01f);
	}

	@Test
	void statueModelUsesTheStoneTexture() throws Exception {
		WadFile min = WadFile.load(rom().resolve("R_MIN.WAD"));
		TextureLibrary lib = new TextureLibrary(min);
		GowModel statue = GowModel.load(min).stream().filter(m -> m.name.equals("MDL_MINStonedBRK")).findFirst().orElseThrow();
		GowModel body = GowModel.load(min).stream().filter(m -> m.name.equals("MDL_MIN")).findFirst().orElseThrow();
		// 같은 이름 MAT_body 가 둘: 몸 (TXR_MIN1_Minotaur_file1) / 석상 (TXR_Minotaurfile1 = GFX_minotaurstone). 앞쪽 가장 가까운 정의로 푼다
		String sm = statue.materials.stream().filter(n -> n.startsWith("MAT_body")).findFirst().orElseThrow();
		String bm = body.materials.stream().filter(n -> n.startsWith("MAT_body")).findFirst().orElseThrow();
		assertNotEquals(sm, bm, "석상과 몸은 다른 MAT_body 정의");
		assertEquals("TXR_Minotaurfile1", texture(lib, sm));
		assertEquals("TXR_MIN1_Minotaur_file1", texture(lib, bm));
		TextureLibrary.Entry stone = lib.textures().stream().filter(e -> e.txr().name().equals("TXR_Minotaurfile1")).findFirst().orElseThrow();
		assertEquals("GFX_minotaurstone", stone.gfx().name());
	}

	/** WadAssets 와 같은 등록: 첫 정의 = 이름, 두 번째부터 = 별칭. */
	private static String texture(TextureLibrary lib, String mat) {
		java.util.Map<String, MaterialResource> map = new java.util.HashMap<>();
		for (int i = 0; i < lib.materials().size(); i++) {
			MaterialResource m = lib.materials().get(i);
			if (map.putIfAbsent(m.name(), m) != null) {
				map.put(TextureLibrary.materialAlias(m.name(), lib.materialTag(i)), m);
			}
		}
		MaterialResource m = map.get(mat);
		return m == null ? null : m.layers().getFirst().texture();
	}

	@Test
	void statueAndFreezeGosResolve() throws Exception {
		WadFile min = WadFile.load(rom().resolve("R_MIN.WAD"));
		EffectLibrary lib = new EffectLibrary();
		lib.add(min);
		EffectLibrary.EffectDef st = lib.resolve("goMINStonedBRK");
		assertNotNull(st);
		assertEquals("MDL_MINStonedBRK", st.model());
		assertNotNull(st.anim(), "파괴 act (조각이 흩어짐)");
		assertNotNull(lib.resolve("goMINFreeze"), "석화 순간 GO");
		HitVolumes hv = HitVolumes.forObject(min, st.skeleton());
		assertEquals(22, devple.gow.engine.fx.MagicFx.statueMfx(st), "CDV_MINStonedBRK 재질 MFX 22 \"Breakable Rock\" (피 대신 돌)");
	}

	/** StoneLook 호출 기록. */
	private static final class Look implements EnemyFighter.StoneLook {
		final List<String> calls = new ArrayList<>();

		@Override
		public void turnToRock(EnemyFighter f, String freezeGo) {
			calls.add("turn:" + freezeGo);
		}

		@Override
		public boolean statue(EnemyFighter f, String statueGo) {
			calls.add("statue:" + statueGo);
			return true;
		}

		@Override
		public void shatter(EnemyFighter f) {
			calls.add("shatter");
		}

		@Override
		public void unfreeze(EnemyFighter f) {
			calls.add("unfreeze");
		}
	}

	private static EnemyFactory.Kind minotaur() throws Exception {
		Path rom = rom();
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		return EnemyFactory.load(rom.resolve("R_MIN.WAD"), perm, -1);
	}

	private static final CombatFile.Attack SQUARE = new CombatFile.Attack(0, 0, new float[9], 0, 1, 520, 2, 2, 0, 0, 0, 0);

	@Test
	void petrifiedMinotaurBecomesAStatueThatTakesSevenDamage() throws Exception {
		EnemyFighter en = minotaur().spawn();
		Look look = new Look();
		en.setStoneLook(look);
		assertEquals(517, en.statueBrkb());
		assertEquals(7, en.statueHpMax());
		assertEquals(771, en.statueOrbEmitter(false), "Brkb 517 +0x28 \"MIN Shatter\"");
		assertEquals(772, en.statueOrbEmitter(true), "+0x2A \"MIN Air Shatter\"");
		assertTrue(en.addFreeze(30000), "Nuke 동결 30000 ≥ 저항 6000 → 석화");
		en.update(DT);
		// freezePose (160) 는 2 프레임짜리 자세: SCR_TurnToRock 인자 0.5 를 넘으면 석화 순간 GO, 0.99 를 넘으면 석상
		for (int i = 0; i < 10; i++) {
			en.update(DT);
		}
		assertEquals(List.of("turn:goMINFreeze", "statue:goMINStonedBRK"), look.calls);
		assertTrue(en.statueShown(), "석상이 몸을 대신 (렌더러는 적 몸을 숨김)");
		Vector3f k = new Vector3f();
		// □ 피해 바이트 2: 2, 4, 6 → 남음, 8 → 부서짐 (Brkb hp 7, 0x1DE4D8)
		for (int i = 0; i < 3; i++) {
			assertEquals(Hittable.Result.HIT, en.receive(SQUARE, null, 2, k));
			assertFalse(en.shattered(), "타수 " + (i + 1));
		}
		assertEquals(1, en.statueHp());
		boolean[] cb = {false};
		en.onShatter(() -> cb[0] = true);
		en.receive(SQUARE, null, 2, k);
		assertTrue(en.shattered());
		assertTrue(cb[0], "Stone Kill 콜백");
		assertEquals("shatter", look.calls.getLast(), "석상 파괴 act");
	}

	@Test
	void stoneWearsOffAndTheStatueGoes() throws Exception {
		EnemyFighter en = minotaur().spawn();
		Look look = new Look();
		en.setStoneLook(look);
		en.addFreeze(30000);
		for (float t = 0; t < en.stoneTime() + 0.2f; t += DT) {
			en.update(DT);
		}
		assertFalse(en.petrified());
		assertFalse(en.statueShown(), "0x1E8AF8: 석상 제거, 몸 다시 보임");
		assertEquals("unfreeze", look.calls.getLast());
	}

	@Test
	void withoutStoneLookTheEnemyBreaksInOneHit() throws Exception {
		EnemyFighter en = minotaur().spawn();
		en.addFreeze(30000);
		for (int i = 0; i < 10; i++) {
			en.update(DT);
		}
		assertFalse(en.statueShown());
		en.receive(SQUARE, null, 2, new Vector3f());
		assertTrue(en.shattered(), "석상이 없으면 (헤드리스) 예전처럼 한 번에");
	}
}
