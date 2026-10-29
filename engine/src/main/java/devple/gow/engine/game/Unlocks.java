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
 * <p>
 * 원작 순서 [확실: 0x1B1128 – 0x1B11E0, 0x230A48, 0x2312DC]: CompleteGame 은 RAM 의 g_unlockMask 에만 비트를 더하고 (0x230118 Unlocks_Apply),
 * 끝 종류 0x29D94C 를 둔다 → 프레임 끝 0x1B339C 가 0x21D8B0(종류) 로 클리어 저장 질문 (HUD SaveGame_Event = 종류). 카드 레코드 +0x40 에는
 * 메모리카드 쓰기 흐름의 단계 0x19 (0x2312DC) 가 {@code 레코드 |= g_unlockMask} 로 적는다 — 세이브 포인트 저장 (0x18 → 0x19) 과 클리어 저장
 * (파일 선택 → 곧 0x19) 모두. 그래서 저장하지 않으면 해금은 전원을 끌 때까지만 남는다 → {@link #grant} (RAM) · {@link #persist} (카드 쓰기).
 */
public final class Unlocks {

	public static final int ALL = 0x1F;
	/** RAM 의 g_unlockMask 0x2A2768 중 카드에 아직 적지 않은 비트 (CompleteGame 이 더함). */
	private static int memory;
	private static final int[] DIFFICULTY_BIT = {0x1, 0x1, 0x2, 0x4};

	private Unlocks() {
	}

	public static Path file(Path cardDir) {
		return cardDir.resolve("unlocks.properties");
	}

	/** 현재 해금 비트: {@code -Dgow.unlockAll} 이면 전부, 아니면 저장된 값 (없으면 0). */
	public static int current() {
		return Boolean.getBoolean("gow.unlockAll") ? ALL : load(SaveGame.dir()) | memory;
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

	/**
	 * CompleteGame 의 끝 종류 (0x29D94C → 0x21D8B0 → HUD SaveGame_Event, FLP SaveScreen 프레임) [확실: 0x1B1160 – 0x1B11C4]:
	 * kind 0 → 난이도 0·1·2 = 2 ("GOW를 모두 마쳤습니다 · 올림푸스의 귀중품들이 풀렸습니다 · 신 모드가 풀렸습니다"), 3 = 3 (신 모드 클리어),
	 * kind 1 → 4 (신들의 시험), kind 2 → 5 (이스터 에그), 그 밖 0 (질문 없음). 세이브 포인트의 저장 질문은 1.
	 */
	public static int endGameKind(int kind, int difficulty) {
		return switch (kind) {
			case 0 -> difficulty >= 0 && difficulty <= 2 ? 2 : difficulty == 3 ? 3 : 0;
			case 1 -> 4;
			case 2 -> 5;
			default -> 0;
		};
	}

	/** CompleteGame: RAM 해금 비트에 더한다 (카드에는 아직 안 씀). @return 카드 + RAM 의 새 해금 비트 */
	public static int grant(Path cardDir, int kind, int difficulty) {
		memory |= bitFor(kind, difficulty);
		return load(cardDir) | memory;
	}

	/** 메모리카드 쓰기 단계 0x19: 카드 레코드 |= RAM 해금 비트. */
	public static void persist(Path cardDir) throws IOException {
		if (memory != 0) {
			save(cardDir, load(cardDir) | memory);
		}
	}

	/** 시험용: RAM 해금 비트 지움 (전원 끔). */
	static void resetMemory() {
		memory = 0;
	}

	/** 클리어 기록: 저장된 비트에 더해 저장하고 새 값을 돌려준다. */
	public static int complete(Path cardDir, int kind, int difficulty) throws IOException {
		int m = load(cardDir) | bitFor(kind, difficulty);
		save(cardDir, m);
		return m;
	}
}
