package devple.gow.engine.game;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 클리어 해금 비트 (원작 g_unlockMask 0x2A2768, 메모리카드 디렉터리 카드 레코드 +0x40 — 11 문서 §2.3 / §5.1).
 * <pre>
 * CompleteGame(kind) (ESC Internal 0x2F):
 *   kind 0 (게임 클리어): 난이도 0·1 → bit0, 2 → bit1, 3 → bit2
 *   kind 1 (신들의 도전 ARENA27 완료) → bit3,  kind 2 → bit4
 * </pre>
 * 메뉴 쪽: (m &amp; 7) → HardMode_Unlocked, m &amp; 8 → Costumes_Unlocked, 귀중품 잠금 문자 (39 문서).
 * 파일 = 세이브 디렉터리의 {@code unlocks.properties} (카드 1 레코드에 해당).
 */
public final class Unlocks {

	public static final int ALL = 0x1F;
	private static final int[] DIFFICULTY_BIT = {0x1, 0x1, 0x2, 0x4};

	private Unlocks() {
	}

	public static Path file(Path cardDir) {
		return cardDir.resolve("unlocks.properties");
	}

	/** 현재 해금 비트: {@code -Dgow.unlockAll} 이면 전부, 아니면 저장된 값 (없으면 0). */
	public static int current() {
		return Boolean.getBoolean("gow.unlockAll") ? ALL : load(SaveGame.dir());
	}

	public static int load(Path cardDir) {
		Path f = file(cardDir);
		if (!Files.exists(f)) {
			return 0;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			p.load(r);
			return Integer.parseInt(p.getProperty("mask", "0")) & ALL;
		} catch (IOException | RuntimeException ex) {
			return 0;
		}
	}

	public static void save(Path cardDir, int mask) throws IOException {
		Properties p = new Properties();
		p.setProperty("mask", Integer.toString(mask & ALL));
		Files.createDirectories(cardDir);
		try (Writer w = Files.newBufferedWriter(file(cardDir), StandardCharsets.UTF_8)) {
			p.store(w, "devple-gow unlocks (g_unlockMask)");
		}
	}

	/** CompleteGame(kind) 의 비트. */
	public static int bitFor(int kind, int difficulty) {
		return switch (kind) {
			case 0 -> DIFFICULTY_BIT[Math.max(0, Math.min(3, difficulty))];
			case 1 -> 0x8;
			case 2 -> 0x10;
			default -> 0;
		};
	}

	/** 클리어 기록: 저장된 비트에 더해 저장하고 새 값을 돌려준다. */
	public static int complete(Path cardDir, int kind, int difficulty) throws IOException {
		int m = load(cardDir) | bitFor(kind, difficulty);
		save(cardDir, m);
		return m;
	}
}
