package devple.gow.engine.game;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.audio.SoundManager;
import devple.gow.engine.script.ScriptHost;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 레벨을 넘어 유지되는 게임 상태: 진행도(PlayerState), 스크립트 GlobalData(ScriptHost), 상주 WAD(R_PERMK), 메시지.
 * 원작의 상주 힙(Perm) + PlayerProgress 에 해당 (11 문서 §4, §6.3).
 */
public final class GameSession {

	private static SoundManager shared;

	/** 앱 전체에서 하나 (OpenAL 장치가 없으면 무음 백엔드). */
	public static synchronized SoundManager sharedSound(Path rom) {
		if (shared == null) {
			shared = SoundManager.open(rom);
		}
		return shared;
	}

	public final Path rom;
	public final WadFile permk;
	public final MessageTable messages;
	public final ScriptHost scriptHost = new ScriptHost();
	public PlayerState player;
	/** HUD 미터가 마지막으로 본 값 (원작 정적 0x29DC34..0x29DC48 — 레벨을 넘어 유지, HudBinding.meter). */
	public final devple.gow.engine.ui.HudBinding.MeterSeen meterSeen = new devple.gow.engine.ui.HudBinding.MeterSeen();
	/** 오디오 (R_PERMK 의 GENERAL/GENERAL2/Hero 뱅크 상주). 메뉴와 게임 씬이 같은 인스턴스를 쓴다. */
	public final SoundManager sound;
	public int difficulty = 1;
	/** 코스튬 (셸 PS2_Costume_Choice, 세이브 options[8]) → R_HERO&lt;n&gt; / R_WPN&lt;n&gt;_&lt;레벨&gt; / /Player/Costume n 배율 (11 문서 §2.4). */
	public int costume;
	/** 누적 플레이 시간 (초): GameScene 이 일시정지가 아닐 때 더한다. 세이브 슬롯 표시용. */
	public float playTime;
	/**
	 * 마지막으로 R2 를 누른 세이브 포인트 번호 (원작 전역 0x2A2704 — 0x1DC3F8 이 영웅 +0x8C 의 가장 가까운 세이브 포인트 번호로 0x22D390 을 부른다) [확실].
	 * 저장 때 슬롯 디렉터리 u16 +0xC 로 (0x2312B0) → 슬롯 이름 = 메시지 600 + 번호, 그림 = R_SAVEPT TXR_SavePt%02d (43 §34).
	 */
	public int savePoint;
	/**
	 * 세계 시작 시각 (playTime 기준) — 원작 0x29D924: 세계 초기화 0x1A6EC0 (레벨 적재 0x1B3070) 가 0 으로, 걷기 이음 (LoadCheck) 은 그대로 둔다.
	 * CheckPoint 의 "시작 뒤 1 초" 거름 (0x1B0234) 이 이것으로 잰다 (엔진 씬은 이음마다 새로 만들어 씬 시각이 0 부터라 쓰지 않는다, 43 문서 §19).
	 */
	float worldStart;
	/** 새 게임 첫 ATHN01A 진입 시 오프닝(Semo01) 재생 여부 (원작 sub_0021efd8). */
	public boolean openingPending;
	/** 직전 Warp 로 떠나온 레벨의 화면 상태 — 원작은 그 레벨을 꺼진 채 메모리에 두므로 바로 Warp 로 돌아오면 그대로 (GameScene.goToLevel, 43 §13.10). */
	public GameScene.Resident warpLeft;
	/**
	 * Warp 순간 영웅 이동 상태 (NavState 이름, null = 모름). 원작 Warp_Jump 0x1B2E08 은 영웅 행렬만 도착 Start 로 바꾸고 (SetMatrix 0x1E79D0) 이동 상태
	 * +0x174 는 그대로 둔다 — 줄만 놓는다 [확실, 43 §13.9]. 그래서 SEWR02 사다리를 오르다 Warp 하면 ATHN03D 우물 사다리에 매달린 채 도착해 계속 오른다
	 * (영상 924–929). 새 씬이 도착 Start 에서 벽 질의로 다시 붙인다 (43 §30).
	 */
	public String warpHeroState;
	/**
	 * 두 슬롯에 올라 있는 레벨의 스크립트 세계 (레벨 이름 대문자 → 세계, 최대 2). 원작 LoadCheck·걸어 넘기는 이음은 "현재 레벨" 표시만 바꾸고 두 슬롯
	 * 스크립트가 그대로 돈다 (0x1BCA10). 같은 레벨을 다시 LoadWad 해도 이미 슬롯에 있으면 읽지 않는다 (0x1BC110: 슬롯 이름이 같으면 건너뜀) [확실].
	 * 엔진 씬은 이음마다 새로 만들므로 이 세계를 넘겨 써서 LevelData·한 번 발동·지연을 잇는다 (43 §18) — 예전엔 ATHN04B 에서 광장으로 돌아오면
	 * ATHN04A 세계가 새로 시작해 사이클롭스 결투 (DMC1 = 0) 와 시민이 다시 나왔다. Warp·Load·다른 레벨 LoadWad 로 슬롯이 바뀌면 버린다.
	 */
	public final java.util.Map<String, devple.gow.engine.script.ScriptWorld> residentWorlds = new java.util.HashMap<>();
	/**
	 * 레벨 기억 (원작 레벨 상태 표 0x336B28 — 이름별 칸 120, {@link devple.gow.engine.script.ScriptWorld.Memory}): 슬롯에서 내려간 레벨의 LevelData ·
	 * 만들어 둔 Creator. 같은 레벨을 다시 읽으면 그 세계를 되살린다 (첫 적재 관문 0x1A9F30 이 거짓). 예전 엔진은 다시 들어간 레벨을 늘 처음부터 시작해
	 * ATHN05D 엘리베이터로 돌아온 ATHN04A 광장에 첫 방문의 시민 (SecTP: 키클롭스 처치 LevelData0 &lt; 3 일 때) 이 다시 나왔다 (영상 8426–8458 엔 없음, 43 §21).
	 * 세이브에 같이 쓴다 (원작 World_Serialize 0x1B3E48 끝이 표 칸 덩어리를 쓴다).
	 */
	public final java.util.LinkedHashMap<String, devple.gow.engine.script.ScriptWorld.Memory> levelMemory = new java.util.LinkedHashMap<>();
	/**
	 * 체크포인트 스냅숏 — 원작은 세이브와 같은 직렬화 (Save_Serialize 0x22D490) 를 0x14000 버퍼 0x3387B8 에 메모리로 쓴다 (0x22D418) [확실]: 새 게임
	 * 레벨 적재가 끝날 때 (상태 9 0x21FD48, 0x29F454 — 메모리카드 불러오기는 읽은 버퍼가 곧 스냅숏), 스크립트 CheckPoint 가 받아들여진 프레임 끝 (0x1B3070, 0x29D960 — 영웅이 점프·떨어짐·줄
	 * (+0x174 &amp; 0x40E) 이거나 HP &lt; 1 이면 다음 프레임으로 미룸), 세이브 포인트 R2 (0x22D390 → 저장 질문). 사망 메뉴 · 실패 메뉴 · 일시정지 메뉴의
	 * "마지막 체크포인트에서 다시 시작" (0x21E3D0) 은 이것을 되읽어 두 슬롯 레벨을 다시 올린다 ({@link SaveGame#applyCheckpoint}, 43 §26).
	 * Warp · Load 는 새 스냅숏을 찍지 않는다 (상태 9 를 거치지 않음) — 레벨을 옮긴 뒤 CheckPoint 전에 죽으면 앞 레벨의 체크포인트로 돌아간다.
	 * null = 아직 없음 (씬이 첫 틱에 찍는다).
	 */
	public SaveGame checkpoint;
	/**
	 * 사망 메뉴에서 "쉬움으로 다시 하기" 를 골랐음 (원작 0x29F484: 체크포인트 복원 0x22DA88 이 스냅숏 난이도 대신 0 을 쓴다 — 메모리카드 불러오기만 지운다)
	 * [확실].
	 */
	public boolean easyRestart;
	/**
	 * 받아들인 CheckPoint 의 스냅숏 대기 (원작 0x29D960 = 1, 보낸 엔티티 이름 — null = 없음): 프레임 끝 0x1B3070 이 영웅 +0x174 &amp; (0x2 | 0x4 | 0x8 | 0x400)
	 * (점프·떨어짐·줄) 이거나 HP &lt; 1 이면 찍지 않고 다음 프레임에 다시 본다 [확실]. 전역이라 이음으로 씬이 바뀌어도 남는다. 되읽기는 지운다 (0x1B15E0 → 0x1B33F0).
	 */
	public String checkpointPending;
	/**
	 * 다음 씬이 이어서 틀 음악 (SND_M_* 이름, "" = 없음, null = 정하지 않음) — 씬이 떠날 때 지금 음악, 세이브·체크포인트 되읽기는 스냅숏의 음악
	 * (원작 음악 그룹 블록 0xEC, 0x22BD80) [근사: 이름만].
	 */
	public String music;

	/** 표에 칸을 연 순서 (처음 읽은 레벨, 0x1B38A8) — 판도라 신전 입구 비우기 규칙용. */
	private final java.util.List<String> levelTable = new java.util.ArrayList<>();
	/** 원작 표 칸 수 (0x1B4308 · 0x1B38A8 이 0x78 까지). */
	static final int LEVEL_TABLE_SIZE = 120;

	/** 레벨이 슬롯에서 내려간다 (이음으로 넘겨 쓰지 않는 세계를 버릴 때): 그 세계의 기억을 표에. */
	public void rememberLevel(String level, devple.gow.engine.script.ScriptWorld w) {
		rememberLevel(level, w, null, null);
	}

	/**
	 * {@link #rememberLevel(String, devple.gow.engine.script.ScriptWorld)} + 그 레벨 Animator 몸 상태 (원작 덩어리의 GO 상태 기록 +0x28, 씬의
	 * {@code Platforms.states()}) · 부순 물체 (+0x40) — 세션 시계 (플레이 시간) 를 같이 적어 다시 읽을 때 흐른 시간을 발동 기록에 더한다
	 * ({@code Memory.aged}).
	 */
	public void rememberLevel(String level, devple.gow.engine.script.ScriptWorld w, List<devple.gow.engine.nav.Platforms.State> anims,
			java.util.Set<String> broken) {
		if (level == null || w == null) {
			return;
		}
		String k = level.toUpperCase(java.util.Locale.ROOT);
		if (!levelTable.contains(k) && levelTable.size() >= LEVEL_TABLE_SIZE) {
			return; // 표가 차면 칸이 없다 (0x1B38A8: 빈 칸을 못 찾음)
		}
		if (!levelTable.contains(k)) {
			levelTable.add(k);
		}
		levelMemory.put(k, w.memory().with(anims, broken, playTime));
	}

	/**
	 * 레벨을 읽는다 (새 세계를 만들기 전): 표에 칸이 있으면 그 기억 (지금 시계로 늙힌 것), 없으면 새 칸 (null = 처음).
	 * 판도라 신전 입구 규칙 0x1B38A8 → 0x1B3A10: 새 칸이 "Pand00B" 이고 바로 앞 칸이 "Pand00A" 면 표를 그 두 칸만 남기고 비운다 (두 칸의 덩어리도 비움) [확실].
	 */
	public devple.gow.engine.script.ScriptWorld.Memory enterLevel(String level) {
		String k = level.toUpperCase(java.util.Locale.ROOT);
		if (levelTable.contains(k)) {
			devple.gow.engine.script.ScriptWorld.Memory m = levelMemory.get(k);
			return m != null ? m.aged(playTime) : null;
		}
		if (k.equals("PAND00B") && !levelTable.isEmpty() && levelTable.getLast().equals("PAND00A")) {
			levelTable.clear();
			levelMemory.clear();
			levelTable.add("PAND00A");
		}
		if (levelTable.size() < LEVEL_TABLE_SIZE) {
			levelTable.add(k);
		}
		return null;
	}

	/** 표에 칸을 연 순서 (세이브용). */
	public java.util.List<String> levelTable() {
		return java.util.Collections.unmodifiableList(levelTable);
	}

	/** 세이브에서 표를 되살린다 (원작 0x1B4450 계열이 덩어리를 읽음). */
	public void restoreLevelTable(java.util.List<String> order, java.util.Map<String, devple.gow.engine.script.ScriptWorld.Memory> memory) {
		levelTable.clear();
		levelMemory.clear();
		for (String k : order) {
			if (levelTable.size() < LEVEL_TABLE_SIZE && !levelTable.contains(k)) {
				levelTable.add(k);
			}
		}
		memory.forEach((k, m) -> {
			if (levelTable.contains(k)) {
				levelMemory.put(k, m);
			}
		});
	}

	/** -Dgow.auto 경로 (gow.auto.route) 중 이음 전환: 다음 레벨 씬이 같은 자동 플레이를 이어 받는다 (검증 하네스, 엔진 전용). */
	public AutoPlay autoCarry;
	/**
	 * 이음 직전 10 초 안에 띄운 구역 제목 번호 (없으면 −1) — 원작 HUD 는 하나라 이음을 넘어도 제목 (StatsZoneReport 10 초) 이 이어지는데 엔진 씬은
	 * 이음마다 HUD 를 새로 만든다 → 새 씬이 다시 띄운다 [근사: 엔진 구조, 페이드 인부터 다시] (43 문서 §19: ATHN04C → 05A "아테네의 지붕").
	 */
	int seamZone = -1;
	/** -Dgow.chain 진행 번호 (48 문서). */
	int chainIndex;

	public GameSession(Path rom) throws IOException {
		this.rom = rom;
		this.permk = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		this.messages = MessageTable.fromWad(permk).orElse(null);
		this.sound = sharedSound(rom);
		if (!sound.loaded().containsKey(permk.name())) {
			sound.load(permk);
		}
		applySoundTweaks(sound, permk);
	}

	/** TWK_Sound /System/Sound/ 의 리버브 프리셋·Base Reverb (29 문서 §8). */
	static void applySoundTweaks(SoundManager snd, WadFile permk) {
		Tweaks t = new Tweaks();
		t.load(permk);
		String node = t.findNode("/System/Sound/");
		if (node == null) {
			return;
		}
		String[] names = {null, "Outdoor Small", "Outdoor Medium", "Outdoor Large", "Indoor Small", "Indoor Medium", "Indoor Large",
				"Underwater"};
		int[] presets = new int[names.length];
		for (int i = 1; i < names.length; i++) {
			presets[i] = t.i(node, names[i], 0);
		}
		snd.setReverbPresets(presets, t.f(node, "Base Reverb", 3500));
		snd.setUnderwaterParams(t.i(node, "Underwater Volume Scale", 512) / 1024f, t.i(node, "Underwater Pitch Offset", -1524),
				t.i(node, "Underwater Music Vol Scale", 768) / 1024f, t.f(node, "Underwater Tween Time", 0.1f));
	}
}
