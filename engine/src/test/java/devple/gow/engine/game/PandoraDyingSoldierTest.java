package devple.gow.engine.game;

import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §32: 판도라의 사원 앞마당 — 죽어 가는 병사 동영상 p00MP02 (영상 3194–3262). PAND00E 는 PAND00B 의 LoadWad (LoadSwitch2) 로만 올라오는
 * 이웃 슬롯이라 (LoadCheck 없음) 장작더미 Mpeg-TriggerVol → FadeOut 0.7 초 → Mpeg-p00MP02play 는 늘 이웃 슬롯 스크립트에서 불린다. 원작 동영상 재생기와
 * 끝 이벤트 501 은 슬롯 구분이 없다 (PostEvent 큐 0x303DB8 하나). 예전 엔진은 이웃 리스너가 PlayMovie 를 버려 동영상 · 501 이 없었고, 그래서
 * Mpeg-EvtMpegDone 의 체크포인트 · 이벤트 200 (PAND00B Gate1-EvtFromMpeg → 관문 열림 → LevelData11 → 키클롭스 스폰 조건) · 병사 MimeGuy-aoXUSAI1
 * (템플릿 300) 이 모두 빠져 관문이 닫힌 채였다.
 */
class PandoraDyingSoldierTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	@Test
	void neighbourSlotMovieOpensTheGateAndSpawnsTheSoldier() throws Exception {
		List<String> movies = new ArrayList<>();
		List<Integer> spawns = new ArrayList<>();
		List<String> animators = new ArrayList<>();
		SceneNav.Host host = new SceneNav.Host() {
			public void heroDied() {
			}

			public void neighbourLevel(ScriptListener.LevelRequest r) {
			}

			public void teleport(float[] pos, float yaw) {
			}

			public void message(int id, float seconds) {
			}

			public void orbs(String emitter, org.joml.Vector3f at) {
			}

			@Override
			public void neighbourMovie(String name, int op, int arg) {
				if (op == 0) {
					movies.add(name);
				}
			}
		};
		// SceneNav.neighbourListener 와 같은 길: 이웃 슬롯 PlayMovie → Host.neighbourMovie, 이웃 SpawnEnemy → Host.neighbourSpawn
		ScriptListener neighbour = new ScriptListener() {
			@Override
			public void movie(String name, int op, int arg) {
				host.neighbourMovie(name, op, arg);
			}

			@Override
			public void spawnEnemy(SpawnRequest r) {
				spawns.add(r.templateId());
			}
		};
		ScriptListener current = new ScriptListener() {
			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				animators.add(source.name());
			}
		};
		ScriptWorld b = new ScriptWorld(level("PAND00B"), new ScriptHost(), current);
		ScriptWorld e = new ScriptWorld(level("PAND00E"), new ScriptHost(), neighbour);
		b.linkEvents(e); // 전역 이벤트 큐 공유 (원작 0x303DB8)
		b.start();
		e.start();
		float[] pyre = {2735, -830, 610}; // 장작더미 앞 (PAND00E Mpeg-TriggerVol 안)
		for (int i = 0; i < 90; i++) {
			b.tick(1 / 60f, pyre[0], pyre[1], pyre[2]);
			e.tick(1 / 60f, pyre[0], pyre[1], pyre[2]);
		}
		assertEquals(List.of("p00MP02"), movies, "이웃 슬롯의 PlayMovie 가 재생기에 간다 (FadeOut 0.7 초 뒤)");
		assertFalse(animators.contains("Gate1-AnimGate"));
		// 동영상 끝: GameScene.playMovie 가 지금 레벨 세계에 501 (인자 0 = 이름) — 공유 큐로 이웃 센서도 받는다
		b.postEvent(501, "p00MP02", 0, 0, b.player());
		for (int i = 0; i < 30; i++) {
			b.tick(1 / 60f, pyre[0], pyre[1], pyre[2]);
			e.tick(1 / 60f, pyre[0], pyre[1], pyre[2]);
		}
		assertTrue(spawns.contains(300), "Mpeg-EvtMpegDone → 병사 MimeGuy-aoXUSAI1 (템플릿 300) " + spawns);
		assertTrue(animators.contains("Gate1-AnimGate"), "이벤트 200 → PAND00B 관문 " + animators);
	}
}
