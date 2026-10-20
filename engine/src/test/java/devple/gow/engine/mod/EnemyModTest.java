package devple.gow.engine.mod;

import devple.gow.engine.combat.AiData;
import devple.gow.engine.combat.AiOverrides;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 적 AI 확장: ai.properties 수치 덮어쓰기와 플러그인 AI 대체 (49 문서 §13). */
class EnemyModTest {

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Content.install(null);
		Plugins.reset();
	}

	private void layerWithAi(String props) throws IOException {
		Path m = Files.createDirectories(tmp.resolve("mod"));
		Files.writeString(m.resolve(AiOverrides.FILE), props);
		Content.install(Content.of(new GameConfig(new Properties()), List.of(m)));
	}

	private static EnemyFactory.Kind load(String wad) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		return EnemyFactory.load(Content.file(rom, wad), WadFile.load(Content.file(rom, Region.of(rom).perm())), -1);
	}

	@Test
	void recordOverridesAreCopiesAndKeysByPriority() throws IOException {
		layerWithAi("""
				*.motion.ground=*2
				min.motion.maxSpeed=12
				050.motion.maxSpeed=20
				r_min.body.joint=head
				50.soldier.cost=7
				min.stone.resist=0x10
				""");
		AiOverrides o = AiOverrides.of(AiOverrides.keys(50, "Minotaur", "R_MIN.WAD"));
		AiData.Motion m = o.apply("motion", AiData.Motion.DEFAULT);
		assertNotSame(AiData.Motion.DEFAULT, m);
		assertEquals(16f, m.ground(), "* 의 배율");
		assertEquals(20f, m.maxSpeed(), "AI1 번호가 WAD 이름보다 우선");
		assertEquals(AiData.Motion.DEFAULT.accel(), m.accel());
		assertEquals(8f, AiData.Motion.DEFAULT.ground(), "원래 레코드는 그대로");
		assertEquals("head", o.apply("body", new AiData.Body(1, 2, 0.5f)).joint());
		assertEquals(7, o.apply("soldier", AiData.Soldier.HERO).cost());
		assertEquals(16, o.apply("stone", new AiData.Stone(6000, 160)).resist());
		assertNull(o.apply("ride", null));
		// 다른 적은 * 만
		AiOverrides sks = AiOverrides.of(AiOverrides.keys(20, null, "R_SKS.WAD"));
		AiData.Motion sm = sks.apply("motion", AiData.Motion.DEFAULT);
		assertEquals(16f, sm.ground());
		assertEquals(AiData.Motion.DEFAULT.maxSpeed(), sm.maxSpeed());
		assertSame(AiData.Soldier.HERO, sks.apply("soldier", AiData.Soldier.HERO));
	}

	@Test
	void noTableMeansNoOverrides() {
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		assertSame(AiOverrides.NONE, AiOverrides.of(AiOverrides.keys(50, "x", "R_MIN.WAD")));
		assertSame(AiData.Motion.DEFAULT, AiOverrides.NONE.apply("motion", AiData.Motion.DEFAULT));
	}

	@Test
	void aiPropertiesChangeSpawnedEnemy() throws Exception {
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		EnemyFighter base = load("R_MIN.WAD").spawn();
		assertEquals(90f, base.maxHp());
		float sight = base.template().sightRange;
		layerWithAi("""
				min.health=200
				050.tmpl.sightRange=*2
				*.damage=2
				50.damage=3
				""");
		EnemyFighter f = load("R_MIN.WAD").spawn();
		assertEquals(200f, f.maxHp());
		assertEquals(200f, f.hp);
		assertEquals(sight * 2, f.template().sightRange, 1e-4);
		assertEquals(3f, f.template().damageMult);
	}

	@Test
	void pluginReplacesEnemyBrain() throws Exception {
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		EnemyFactory.Kind min = load("R_MIN.WAD"), sks = load("R_SKS.WAD");
		assertNull(min.spawn().controller(), "start 전에는 원래 AI");

		Plugins.start();
		assertTrue(Plugins.active().stream().anyMatch(p -> p instanceof ExampleBrainPlugin), "META-INF/services 로 찾음");
		EnemyFighter f = min.spawn();
		EnemyFighter foe = sks.spawn();
		assertNull(foe.controller(), "등록하지 않은 적은 원래 AI");
		ExampleBrainPlugin.Walker w = assertInstanceOf(ExampleBrainPlugin.Walker.class, f.controller());
		foe.pos.set(400, 0, 0);
		f.setFoe(foe);
		for (int i = 0; i < 60; i++) {
			f.update(1 / 30f);
		}
		assertEquals(60, w.calls);
		assertTrue(f.pos.x > 20, "대상 쪽으로 걸음: " + f.pos);
		assertEquals(0, f.brain().attacksStarted, "원래 결정 트리는 돌지 않음");

		int before = ExampleBrainPlugin.frames;
		Plugins.frame(1 / 30f);
		assertEquals(before + 1, ExampleBrainPlugin.frames);
	}
}
