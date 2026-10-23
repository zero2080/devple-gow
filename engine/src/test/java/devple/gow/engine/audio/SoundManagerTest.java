package devple.gow.engine.audio;

import devple.gow.tools.content.Content;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 29 문서: 헤드리스 SoundManager — 이름/해시 조회, 그레인 실행, VAG 팩, 음악 스트림, SoundController 그룹. */
class SoundManagerTest {

	private SoundManager mgr(String... wads) throws Exception {
		Path rom = SoundBankTest.rom();
		SoundManager m = SoundManager.headless(rom);
		for (String w : wads) {
			m.load(WadFile.load(Content.file(rom, w)));
		}
		return m;
	}

	private static List<NullAudioBackend.Started> log(SoundManager m) {
		return ((NullAudioBackend) m.backend()).log();
	}

	@Test
	void nameAndHashLookup() throws Exception {
		SoundManager m = mgr("R_PERMK.WAD", "R_HERO0.WAD", "R_MIN.WAD", "ATHN01A.WAD");
		for (String n : List.of("SND_PICKUPS_ORBS_E", "SND_MINO_SNORT", "SND_TRANSFORM_LP", "SND_IG_POPUP")) {
			assertTrue(m.has(n), n);
			assertEquals(n, m.nameOf(TweakFile.hash(n)), n + " 해시");
		}
		assertEquals("GEN1", m.find("SND_PICKUPS_ORBS_E").bank().bankId());
		assertEquals("MIN", m.find("SND_MINO_SNORT").bank().bankId());
		// 전투 이벤트 SOUND 해시 (CombatFile Event.hash) 그대로 재생
		int h = m.play(TweakFile.hash("SND_MINO_SNORT"), new Vector3f(5, 0, 0));
		assertTrue(h > 0);
		NullAudioBackend.Started s = log(m).get(log(m).size() - 1);
		assertEquals("clip", s.kind());
		assertTrue(s.name().startsWith("MIN@"), s.name());
		assertEquals(16040, s.rate(), 5);
		assertTrue(s.voice().pos != null && s.voice().gain > 0.3f);
		assertEquals(0, m.play(0x12345678, null));
	}

	@Test
	void uiAndPickupSounds() throws Exception {
		SoundManager m = mgr("R_PERMK.WAD");
		int before = log(m).size();
		assertTrue(m.play("SND_IG_POPUP") > 0);
		assertTrue(m.play("SND_PICKUPS_ORBS_E") > 0);
		m.update(0.05f);
		assertTrue(log(m).size() > before, "보이스 시작");
	}

	@Test
	void footstepRegisterSelectsSurfaceVariant() throws Exception {
		SoundManager m = mgr("R_PERMK.WAD");
		String[] labels = new String[3];
		for (int r = 0; r < 3; r++) {
			int n = log(m).size();
			assertTrue(m.play("SND_FOOTSTEP_DIRT", null, r) > 0);
			m.update(0.5f);
			assertTrue(log(m).size() > n, "reg0=" + r + " 톤 없음");
			labels[r] = log(m).get(n).voice().label;
		}
		System.out.println("발소리 분기: " + String.join(" | ", labels));
		assertTrue(labels[0].startsWith("SND_FOOTSTEP_DIRTSTEP"), labels[0]);
		assertTrue(labels[1].startsWith("SND_FOOTSTEP_DIRTWALK"), labels[1]);
		assertTrue(labels[2].startsWith("SND_FOOTSTEP_DIRTRUN"), labels[2]);
	}

	@Test
	void loopingSoundStopsOnRequest() throws Exception {
		SoundManager m = mgr("R_PERMK.WAD");
		int h = m.play("SND_TRANSFORM_LP");
		assertTrue(h > 0);
		for (int i = 0; i < 60; i++) {
			m.update(1 / 30f);
		}
		assertTrue(m.playing(h), "루프는 2초 뒤에도 재생 중");
		m.stop(h);
		for (int i = 0; i < 300 && m.playing(h); i++) {
			m.update(1 / 30f);
		}
		assertFalse(m.playing(h), "정지 후 꼬리까지 끝남");
	}

	@Test
	void vagPackDialogueViaPlugin() throws Exception {
		SoundManager m = mgr("R_MIN.WAD");
		int n = log(m).size();
		assertTrue(m.play("SND_MINO_VODIE", new Vector3f()) > 0);
		m.update(0.1f);
		NullAudioBackend.Started s = log(m).get(n);
		assertEquals("MINDIE_1", s.name());
		assertEquals(16000, s.rate());
	}

	@Test
	void musicGroupStreamsVpk() throws Exception {
		SoundManager m = mgr("ATHN01A.WAD");
		int h = m.music("SND_M_ATH1C1_4", 0);
		assertTrue(h > 0);
		m.update(0.25f);
		NullAudioBackend.Started s = log(m).stream().filter(x -> x.kind().equals("stream")).findFirst().orElseThrow();
		assertEquals("ATH1C1_4.VPK", s.name());
		assertEquals("SND_M_ATH1C1_4", m.currentMusic());
		NullAudioBackend b = (NullAudioBackend) m.backend();
		assertTrue(b.streamed(s.id()) > 1000, "VPK 블록 디코딩");
		m.musicParam(1, 0, 5);
		m.stopMusic(3);
		m.update(0.1f);
		assertNull(m.currentMusic());
		assertFalse(b.playing(s.id()));
	}

	@Test
	void soundControllerGroupUsesEmitterSlots() throws Exception {
		SoundManager m = mgr("ATHN01A.WAD");
		int n = log(m).size();
		m.soundGroup("SNDGrpAmbDeckofShip", 1, new Vector3f(-240, -48, -232));
		m.update(0.1f);
		assertTrue(log(m).size() > n, "SND_OCEANSTORM_LP 시작");
		String first = log(m).get(n).voice().label;
		assertTrue(first.startsWith("SND_OCEANSTORM_LP"), first);
		int n2 = log(m).size();
		m.soundGroup("SNDGrpAmbDeckofShip", 2, null);
		m.update(0.1f);
		assertTrue(log(m).size() > n2);
		assertNotEquals(first, log(m).get(n2).voice().label);
		m.soundGroup("SNDGrpAmbDeckofShip", 0, null);
		for (int i = 0; i < 300 && m.active() > 0; i++) {
			m.update(1 / 30f);
		}
		assertEquals(0, m.active());
	}

	/**
	 * SND_SHAKE_* (GENERAL2, DPMS cmd 0x16 모터 1) → 카메라 흔들림 요청 (원작 IOP 상태 버퍼 0x29CAF0 +0x25C → 0x228C70): 템플릿 = 인자 (SMALL 1 … HUGE 4, SPECIAL 500),
	 * 새 요청마다 serial, 크기 = 들림 크기 (거리 감쇠). 모터 0 (SND_RUMBLE_*) 은 흔들림이 아니다.
	 */
	@Test
	void shakeSoundRequestsCameraShake() throws Exception {
		SoundManager m = mgr("R_PERMK.WAD");
		assertNull(m.shakeRequest());
		m.listener(new Vector3f(), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
		m.play("SND_RUMBLE_BIG", null);
		assertNull(m.shakeRequest(), "모터 0 = 패드 진동");
		m.play("SND_SHAKE_BIG", null);
		SoundManager.ShakeRequest r = m.shakeRequest();
		assertEquals(3, r.template());
		assertEquals(120 / 127f, r.scale(), 1e-3f, "2D: 소리 볼륨 120");
		m.play("SND_SHAKE_HUGE", new Vector3f(10000, 0, 0));
		SoundManager.ShakeRequest r2 = m.shakeRequest();
		assertEquals(4, r2.template());
		assertEquals(r.serial() + 1, r2.serial());
		assertEquals(0, r2.scale(), 1e-6f, "far 밖이면 0");
	}
}
