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
	/** 누적 플레이 시간 (초): GameScene 이 일시정지가 아닐 때 더한다. 세이브 슬롯 표시용. */
	public float playTime;
	/** 새 게임 첫 ATHN01A 진입 시 오프닝(Semo01) 재생 여부 (원작 sub_0021efd8). */
	public boolean openingPending;

	public GameSession(Path rom) throws IOException {
		this.rom = rom;
		this.permk = WadFile.load(rom.resolve("R_PERMK.WAD"));
		this.messages = MessageTable.fromWad(permk).orElse(null);
		this.sound = sharedSound(rom);
		if (!sound.loaded().containsKey(permk.name())) {
			sound.load(permk);
		}
	}
}
