package devple.gow.engine.game;

import devple.gow.engine.audio.SoundManager;
import devple.gow.engine.script.ScriptHost;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Path;

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
	/** 오디오 (R_PERMK 의 GENERAL/GENERAL2/Hero 뱅크 상주). 메뉴와 게임 씬이 같은 인스턴스를 쓴다. */
	public final SoundManager sound;
	public int difficulty = 1;
	/** 코스튬 (셸 PS2_Costume_Choice, 세이브 options[8]) → R_HERO&lt;n&gt; / R_WPN&lt;n&gt;_&lt;레벨&gt; / /Player/Costume n 배율 (11 문서 §2.4). */
	public int costume;
	/** 누적 플레이 시간 (초): GameScene 이 일시정지가 아닐 때 더한다. 세이브 슬롯 표시용. */
	public float playTime;
	/** 새 게임 첫 ATHN01A 진입 시 오프닝(Semo01) 재생 여부 (원작 sub_0021efd8). */
	public boolean openingPending;
	/** 직전 Warp 로 떠나온 레벨의 화면 상태 — 원작은 그 레벨을 꺼진 채 메모리에 두므로 바로 Warp 로 돌아오면 그대로 (GameScene.goToLevel, 43 §13.10). */
	public GameScene.Resident warpLeft;
	/** -Dgow.chain 진행 번호 (48 문서). */
	int chainIndex;

	public GameSession(Path rom) throws IOException {
		this.rom = rom;
		this.permk = WadFile.load(rom.resolve("R_PERMK.WAD"));
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
