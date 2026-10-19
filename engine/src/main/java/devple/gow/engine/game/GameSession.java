package devple.gow.engine.game;

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

	public final Path rom;
	public final WadFile permk;
	public final MessageTable messages;
	public final ScriptHost scriptHost = new ScriptHost();
	public PlayerState player;
	public int difficulty = 1;

	public GameSession(Path rom) throws IOException {
		this.rom = rom;
		this.permk = WadFile.load(rom.resolve("R_PERMK.WAD"));
		this.messages = MessageTable.fromWad(permk).orElse(null);
	}
}
