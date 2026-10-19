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

	/** SpawnEnemy 발동: 생성된 액터(위치/그룹 = 스폰 지점/Filter), 템플릿 번호, 순찰 경로 이름. */
	record SpawnRequest(ScriptEntity source, ScriptActor actor, int templateId, String patrol, float[] world) {
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

	/** Vis 이펙터: 대상 오브젝트들의 속성 슬롯 0..8, 16, 17 값 (0 = 변경 없음, 1 켬, 2 끔). */
	default void vis(ScriptEntity source, List<String> objects, int[] values) {
	}

	/** Animator 이펙터: 대상 오브젝트에 애니메이션 재생. */
	default void animate(ScriptEntity source, List<String> objects, String anim) {
	}

	/** Creator 이펙터 (오브젝트 생성, 슬롯 의미 미확정). */
	default void create(ScriptEntity source) {
	}

	/** Entity:0xD/0xE/0x10 동영상 (op 0 재생, 1 제어, 2 미리 로드). */
	default void movie(String name, int op, int arg) {
	}

	/** 그 밖의 Internal 함수 호출 (인자 = push 순서). */
	default void internal(int func, List<Object> args) {
	}

	/** DebugMsg 슬롯 / Printf. */
	default void debug(ScriptEntity source, String text) {
	}
}
