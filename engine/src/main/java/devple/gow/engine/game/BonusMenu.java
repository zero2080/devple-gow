package devple.gow.engine.game;

import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.ui.MessageTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 메인 메뉴 "귀중품"(보너스) 목록의 엔진 쪽 (원작 sub_001baa68 초기화 / sub_001bab10 PS2_BonusScroll / sub_001bad40 매 프레임 /
 * sub_001bb140 행 채우기, 39 문서).
 * <p>
 * FLP_ShellK 의 TreasureOn..TreasureOff 화면은 표시만 한다: 8줄({@code PS2_BonusName1..8}, 4번째 줄 = 커서),
 * 잠금 표시({@code PS2_BonusLocked1..8}), 커서 항목 설명 3줄({@code PS2_BonusDesc1..3}). 입력은 FSCommand 로 온다.
 * <pre>
 *   PS2_BonusScroll 0 / 1   ↑ 누름 / 뗌      (누르고 있으면 가속 스크롤: 이동 칸 = floor(speed²), speed 1 → +2/초)
 *   PS2_BonusScroll 2 / 3   ↓ 누름 / 뗌
 *   PS2_BonusScroll 4       화면 열림/닫힘 → 상태 초기화 + 행 다시 채움
 *   PS2_BonusScroll 5       ○ — 커서 항목 실행 (동작 문자열 1번째 영상 / 레벨 / 연속 재생)
 *   PS2_BonusScroll 6 / 7   □ / ○ — 2·3번째 영상 (동작 길이 ≥ 6 / ≥ 9 일 때만; 원작 데이터엔 해당 항목 없음)
 * </pre>
 * 항목 = 메시지 900+i (줄 ≥ 2 인 것 개수가 항목 수). 줄 0 이름, 줄 1 = 잠금 문자 + 동작, 줄 2..4 설명, 줄 5..7 잠긴 설명.
 * 잠금 문자 ({@link #locked(char, int)}): S 항상 열림, E 게임 클리어(해금 비트 0..2), H 어려움 이상 클리어(1..2), G 신 모드 클리어(2),
 * A 신들의 시험 완료(3), F 비트 4, 그 밖 = 잠김. 동작: {@code Mxx} 영상 BonusXX, {@code A} ARENA10(신들의 시험), {@code G} OLYMP04(캐릭터 공동 묘지),
 * {@code S} 게임 속 동영상 연속 재생 (Semo01..10, 13; 하나라도 건너뛰면 중단).
 */
public final class BonusMenu {

	/** 영상 재생 / 레벨 시작을 맡는 쪽 (ShellScene, 테스트). */
	public interface Host {
		/** 영상 하나 재생. 끝나면 done.accept(건너뛰었는지). */
		void playMovie(String name, java.util.function.Consumer<Boolean> done);

		/** 보너스 레벨 시작 (원작: 0x29F4B0 → NewGame 상태가 Athn01A 대신 이 레벨, 난이도 1). */
		void startLevel(String wad);

		/** 효과음 (SND_MM_SELECT). */
		default void sound(String name) {
		}
	}

	public static final int FIRST_MESSAGE = 900, MAX_ENTRIES = 100, ROWS = 8;
	/** 커서 줄 = 4번째 (Bonus4 클립, 1.2배). */
	public static final int CENTER = 3;
	/** 'S' 항목 연속 재생 목록 (원작 표 0x29DA98). */
	public static final List<String> STORY = List.of("Semo01", "Semo02", "Semo03", "Semo04", "Semo05", "Semo06", "Semo07",
			"Semo08", "Semo09", "Semo10", "Semo13");
	/** 영상이 끝난 뒤 입력 잠금 시간 (원작 0x29DA7C = 60프레임). */
	static final float COOLDOWN_SEC = 1.0f;
	/** 디버그: 모든 해금 비트 (게임 3종 클리어 + 신들의 시험 + 비트 4). */
	public static final int ALL_UNLOCKED = 0x1F;

	/** 메시지 한 항목. action = 잠금 문자 뒤 문자열 (예 "M03"). */
	public record Entry(String name, char lock, String action, List<String> desc, List<String> lockedDesc) {
	}

	private final UiVariables vars;
	private final List<Entry> entries = new ArrayList<>();
	private final int unlockBits;
	private final Host host;
	private int cursor;
	/** 스크롤 방향 (-1 / +1, 0 = 정지), 누른 시점 커서, 속도. */
	private int scrollDir, scrollStart;
	private float scrollSpeed;
	/** 실행 중 (영상/연속 재생/레벨) — 원작 +0x1520. 이때는 4 외의 명령 무시. */
	private boolean busy;
	private float cooldown;
	private String lastAction;

	public BonusMenu(UiVariables vars, MessageTable messages, int unlockBits, Host host) {
		this.vars = vars;
		this.unlockBits = unlockBits;
		this.host = host;
		for (int i = 0; i < MAX_ENTRIES && messages != null; i++) {
			String m = messages.get(FIRST_MESSAGE + i);
			String[] lines = m == null ? new String[0] : m.split("\n", -1);
			if (lines.length < 2) {
				continue;
			}
			String code = lines[1].isEmpty() ? "L" : lines[1];
			entries.add(new Entry(lines[0], code.charAt(0), code.substring(1), range(lines, 2), range(lines, 5)));
		}
		refresh();
	}

	/** 해금 비트: {@code -Dgow.unlockAll} 이면 전부, 아니면 저장된 클리어 기록 ({@link Unlocks}). */
	public static int unlockBits() {
		return Unlocks.current();
	}

	private static List<String> range(String[] lines, int from) {
		List<String> out = new ArrayList<>(3);
		for (int i = from; i < from + 3; i++) {
			out.add(i < lines.length ? lines[i] : "");
		}
		return out;
	}

	/** 원작 sub_001bb140 의 잠금 문자 표 0x2B70B0 (문자 − 'A'). 해금 비트 = 0x2A2768 (11 문서 §2.3). */
	public static boolean locked(char c, int bits) {
		return switch (c) {
			case 'S' -> false;
			case 'E' -> (bits & 0x7) == 0;
			case 'H' -> (bits & 0x6) == 0;
			case 'G' -> (bits >> 2 & 1) == 0;
			case 'A' -> (bits >> 3 & 1) == 0;
			case 'F' -> (bits >> 4 & 1) == 0;
			default -> true; // 'L' 및 표에 없는 문자 (원작은 이전 값 유지 — 데이터에 없음)
		};
	}

	public List<Entry> entries() {
		return entries;
	}

	public int cursor() {
		return cursor;
	}

	public boolean busy() {
		return busy;
	}

	/** 마지막으로 실행한 동작 (테스트/로그용, 예 "Bonus03", "ARENA10", "Semo01..13"). */
	public String lastAction() {
		return lastAction;
	}

	/** FlpPlayer 명령 리스너에 연결. */
	public void command(String cmd, String arg) {
		if (!"PS2_BonusScroll".equals(cmd)) {
			return;
		}
		int a;
		try {
			a = Integer.parseInt(arg.trim());
		} catch (RuntimeException ex) {
			return;
		}
		scroll(a);
	}

	void scroll(int a) {
		if ((busy || cooldown > 0) && a != 4) {
			return;
		}
		switch (a) {
			case 0, 2 -> {
				scrollDir = a == 0 ? -1 : 1;
				scrollSpeed = 1f;
				scrollStart = cursor;
				step();
			}
			case 1, 3 -> scrollDir = 0;
			case 4 -> {
				scrollDir = 0;
				busy = false;
				cooldown = 0;
				refresh();
			}
			case 5 -> select(1);
			case 6 -> select(4);
			case 7 -> select(7);
			default -> {
			}
		}
	}

	/** 매 프레임: 누르고 있는 동안 가속 스크롤, 영상 뒤 입력 잠금 해제. */
	public void update(float dt) {
		if (cooldown > 0 && (cooldown -= dt) <= 0) {
			cooldown = 0;
			vars.set("PS2_EnableButtons", 1);
		}
		if (scrollDir != 0 && !busy) {
			scrollSpeed += 2f * dt;
			step();
		}
	}

	private void step() {
		int n = (int) (scrollSpeed * scrollSpeed);
		int idx = Math.max(0, Math.min(entries.size() - 1, scrollStart + scrollDir * n));
		if (idx != cursor) {
			cursor = idx;
			refresh();
		}
	}

	/** 동작 문자열의 pos 위치(1 / 4 / 7)부터 실행. 1 = 첫 문자로 종류 판정, 4 / 7 = 'M' 일 때 2·3번째 영상. */
	private void select(int pos) {
		if (scrollDir != 0 || entries.isEmpty()) {
			return;
		}
		Entry e = entries.get(cursor);
		if (locked(e.lock, unlockBits)) {
			return; // 원작: 잠긴 항목은 동작 문자열이 "" 로 채워진다
		}
		String act = e.action;
		if (pos > 1) {
			if (act.length() >= pos + 2 && act.charAt(0) == 'M') {
				movie("Bonus" + act.substring(pos - 1, pos + 1));
			}
			return;
		}
		if (act.isEmpty()) {
			return;
		}
		switch (act.charAt(0)) {
			case 'M' -> {
				if (act.length() >= 3) {
					movie("Bonus" + act.substring(1, 3));
				}
			}
			case 'A' -> level("ARENA10");
			case 'G' -> level("OLYMP04");
			case 'S' -> story(0);
			default -> {
			}
		}
	}

	private void movie(String name) {
		begin(name);
		host.playMovie(name, skipped -> end());
	}

	private void story(int i) {
		if (i == 0) {
			begin("Semo01..13");
		}
		if (i >= STORY.size()) {
			end();
			return;
		}
		host.playMovie(STORY.get(i), skipped -> {
			if (skipped) {
				end(); // 원작: 0x29D500(건너뜀) 이면 연속 재생 중단
			} else {
				story(i + 1);
			}
		});
	}

	private void level(String wad) {
		begin(wad);
		host.startLevel(wad);
	}

	private void begin(String what) {
		busy = true;
		lastAction = what;
		vars.set("PS2_EnableButtons", 0);
		host.sound("SND_MM_SELECT");
	}

	private void end() {
		busy = false;
		cooldown = COOLDOWN_SEC;
	}

	/** 8줄 = 커서 −3 .. +4 (원작 sub_001bad40 → sub_001bb140 × 8). 범위 밖 = 이름 "" + 잠김. */
	private void refresh() {
		for (int r = 0; r < ROWS; r++) {
			int i = cursor - CENTER + r;
			boolean in = i >= 0 && i < entries.size();
			Entry e = in ? entries.get(i) : null;
			boolean lk = !in || locked(e.lock, unlockBits);
			vars.set("PS2_BonusName" + (r + 1), in ? e.name : "");
			vars.set("PS2_BonusLocked" + (r + 1), lk ? 1 : 0);
			if (r == CENTER) {
				List<String> d = !in ? List.of("", "", "") : lk ? e.lockedDesc : e.desc;
				for (int k = 0; k < 3; k++) {
					vars.set("PS2_BonusDesc" + (k + 1), d.get(k));
				}
			}
		}
	}
}
