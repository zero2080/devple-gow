package devple.gow.engine.script;

import java.util.List;

/**
 * {@link ScriptWorld} → 게임 통지 (모두 기본 무시). 13 문서 §5.
 */
public interface ScriptListener {

	/** 레벨 이동 요청 종류 (GameMgr 메시지 2..7). */
	enum LevelOp { LOAD, LOAD_WAD, LOAD_FOR_WARP, WARP, LOAD_CHECK, GOTO }

	/** 레벨 이동 요청. wad 는 대문자, GOTO 는 entry = 엔티티 이름, pos = 그 위치(없으면 null). */
	record LevelRequest(LevelOp op, String wad, String entry, float[] pos) {
	}

	/**
	 * SpawnEnemy 발동: 생성된 액터(위치/그룹 = 스폰 지점/Filter), 템플릿 번호, 순찰 경로 이름,
	 * 생성 액션 (슬롯 7 SpawnAction, -1 = 없음: 원작 Enemy1 초기화가 이 CombatFile 액션을 요청 — 500 SPAWN, 504 SKSWALLSPAWN …, 32 문서).
	 */
	record SpawnRequest(ScriptEntity source, ScriptActor actor, int templateId, String patrol, float[] world, int spawnAction) {
		public SpawnRequest(ScriptEntity source, ScriptActor actor, int templateId, String patrol, float[] world) {
			this(source, actor, templateId, patrol, world, -1);
		}
	}

	/** EventTransmitter → 오브젝트 이벤트. actor 가 null 이면 objectName(소유 GO) 대상. */
	record ObjectEvent(ScriptEntity source, String objectName, ScriptActor actor, int event, int arg1, int arg2) {
	}

	/** 안내문 (Internal:0xA ShowMessage, 0x1F ShowItemCard). */
	default void message(int msgId, float seconds) {
	}

	default void checkpoint(ScriptEntity source) {
	}

	default void level(LevelRequest r) {
	}

	/** LoadCheck 응답 (GameFlow 가 없을 때). true 면 다음 WAD 준비 완료. */
	default boolean loadCheck(String wad) {
		return false;
	}

	/** DeathZone 그룹(이름 접두사 "DeathZone")의 송신기가 액터에 이벤트를 보냄, 또는 이벤트 1029(제거) 수신. */
	default void deathZone(ScriptActor actor, ScriptEntity source, int event, int arg) {
	}

	default void objectEvent(ObjectEvent e) {
	}

	default void spawnEnemy(SpawnRequest r) {
	}

	/** SoundController 발동 (op 1 켜기, 0 끄기, 2 ... 슬롯 0). group = 엔티티 이름 접두사 (SNDGrp... / MusicGrp...). */
	default void sound(ScriptEntity source, String group, int op) {
	}

	/** 음악 / 효과음 Internal 함수 (0x1B MusicGroupStart, 0x1C MusicGroupStop, 0x1D MusicGroupParam, 0x38 PlaySound). */
	default void music(String func, List<Object> args) {
	}

	/** Internal:0x8 카메라 전환. */
	default void camera(String name) {
	}

	/**
	 * Internal 0xD / 0xE (0x1B098C / 0x1B09C8 → 0x1EE740 / 0x1EE750): 카메라 관리자 +0x808 (지금 카메라 이름) / +0x820 (바로 앞 카메라 이름) —
	 * 전환 0x1EEE04 가 +0x820 ← +0x808, +0x808 ← 새 이름 [확실]. 없으면 "" (예 ATHN06C oraclPuzzle-a06Mp03Done: 영상 끝에 카메라가 UprCam21 / UprCam3,
	 * PAND03B EndDoor-CamOff: 앞 카메라로 되돌림).
	 */
	default String cameraName(boolean previous) {
		return "";
	}

	/** Vis 이펙터: 대상 오브젝트들의 속성 슬롯 0..8, 16, 17 값 (0 = 변경 없음, 1 켬, 2 끔). */
	default void vis(ScriptEntity source, List<String> objects, int[] values) {
	}

	/** Animator 이펙터: 대상 오브젝트에 애니메이션 재생. */
	default void animate(ScriptEntity source, List<String> objects, String anim) {
	}

	/** Creator 이펙터 (오브젝트 생성, 슬롯 의미 미확정). */
	default void create(ScriptEntity source) {
	}

	/**
	 * Entity:0xF (원작 0x1AFD58 → 0x29D954 = 1): 프레임 끝 0x1B3360 이 0x21F598 로 게임을 접고 셸 (타이틀) 로 — OLYMP02 크레딧 Bonus20 끝
	 * "PlayMovie-Restarting" 하나뿐.
	 */
	default void restartGame() {
	}

	/** Entity:0xD/0xE/0x10 동영상 (op 0 재생, 1 제어, 2 미리 로드). */
	default void movie(String name, int op, int arg) {
	}

	/**
	 * Entity:0x5 FadeOut(r, g, b, 초) (out = true) / Entity:0x6 FadeIn(초) (out = false, 색 0) — 원작 0x1AFA20 / 0x1AFAD4 가 화면 페이드
	 * 상태 0x303D50 을 바꾸고 매 프레임 0x1AF648 이 색 사각형 0x2C8F50 을 갱신한다 (43 §16).
	 */
	default void fade(float r, float g, float b, float seconds, boolean out) {
	}

	/** 그 밖의 Internal 함수 호출 (인자 = push 순서). */
	default void internal(int func, List<Object> args) {
	}

	/** DebugMsg 슬롯 / Printf. */
	default void debug(ScriptEntity source, String text) {
	}
}
