package devple.gow.engine.game;

import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.ui.MessageTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 메모리카드 화면 상태기 (원작 MemCard_ReadFlow 0x230248 / MemCard_WriteFlow 0x230A98 의 UI 쪽, 22 문서 §7).
 * <p>
 * FLP(FLP_ShellK / FLP_HUDK 의 SaveLoadMenu) 는 엔진이 {@code PS2_MemoryCard_Event = n} 으로 화면 n(MemoryScreen1..27)을 띄우면
 * 그 화면을 보여 주고, 선택 결과를 {@code FSCommand:PS2_MemoryCard_Select arg} 로 돌려준다 (△ = 0, 예/아니오 = 1/0,
 * 카드 슬롯 1/2, 파일 1..4). 0 을 보내면 메뉴가 닫힌다. 이 클래스가 원작 실행 파일 쪽 순서를 맡는다.
 * <pre>
 * 불러오기: 21 카드 선택 → 23 파일 선택 → 10 불러올까? → 11 불러오는 중 → 13 완료 → 0
 * 저장하기: 21 카드 선택 → 22 파일 선택 → 15 덮어쓸까? / 16 새로 만들까? → 17 저장 중 → 18 완료(○) → 0
 * 오류:     2 카드 없음(○ → 21), 14 GOW 파일 없음(○ → 21), 12 불러오기 오류(○ → 23), 19 저장 오류(○ → 22)
 * </pre>
 * 카드 1 = 세이브 디렉터리 (파일 4개 = slot0..3.save), 카드 2 = 없음.
 * 이동 키는 FLP 가 처리: 카드 슬롯·파일 ←/→ (SaveLoadLeft/Right), 예/아니오 ↑/↓ (UpDownMemoryCard) (기본 = 아니오). 21/22/23 에서 고르면 FLP 가 입력을 잠그고
 * (EnableButton=2) 다음 화면을 기다리므로, 선택을 무시할 때도 화면을 다시 보낸다.
 */
public final class MemoryCardFlow {

	public enum Mode { LOAD, SAVE }

	public enum Result { RUNNING, LOADED, SAVED, CANCELLED }

	public static final int FILES = 4;
	/** 진행 화면(불러오는 중 / 저장 중 / 완료) 최소 표시 시간 (초). */
	static final float BUSY_SEC = 1.0f;

	static final int NO_CARD = 2, LOAD_ASK = 10, LOADING = 11, LOAD_ERROR = 12, LOAD_DONE = 13, NO_FILES = 14,
			OVERWRITE_ASK = 15, CREATE_ASK = 16, SAVING = 17, SAVE_DONE = 18, SAVE_ERROR = 19, CARD_SELECT = 21,
			SAVE_SELECT = 22, LOAD_SELECT = 23;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

	private final UiVariables vars;
	private final MessageTable messages;
	private final Path cardDir;
	private final Mode mode;
	private final Supplier<SaveGame> toSave;
	private int state;
	private int file;
	private float timer;
	private Result result = Result.RUNNING;
	private SaveGame loaded;

	public MemoryCardFlow(UiVariables vars, MessageTable messages, Path cardDir, Mode mode, Supplier<SaveGame> toSave) {
		this.vars = vars;
		this.messages = messages;
		this.cardDir = cardDir;
		this.mode = mode;
		this.toSave = toSave;
	}

	public static MemoryCardFlow load(UiVariables vars, MessageTable messages, Path cardDir) {
		return new MemoryCardFlow(vars, messages, cardDir, Mode.LOAD, null);
	}

	public static MemoryCardFlow save(UiVariables vars, MessageTable messages, Path cardDir, Supplier<SaveGame> toSave) {
		return new MemoryCardFlow(vars, messages, cardDir, Mode.SAVE, toSave);
	}

	public static Path file(Path cardDir, int n) {
		return cardDir.resolve("slot" + n + ".save");
	}

	public void start() {
		show(CARD_SELECT);
	}

	public Result result() {
		return result;
	}

	public int state() {
		return state;
	}

	/** 불러온 세이브 ({@link Result#LOADED} 일 때). */
	public SaveGame loaded() {
		return loaded;
	}

	/** 불러온/저장한 파일 번호 (0..3). */
	public int fileIndex() {
		return file;
	}

	/** FlpPlayer 명령 리스너에 연결. */
	public void command(String cmd, String arg) {
		if (!"PS2_MemoryCard_Select".equals(cmd) || result != Result.RUNNING) {
			return;
		}
		int a;
		try {
			a = Integer.parseInt(arg.trim());
		} catch (RuntimeException ex) {
			return;
		}
		select(a);
	}

	void select(int a) {
		switch (state) {
			case CARD_SELECT -> {
				if (a == 0) {
					finish(Result.CANCELLED);
				} else if (a != 1) {
					show(NO_CARD);
				} else {
					vars.set("PS2_MemoryCard_CardSlot", a);
					boolean any = fillSlots();
					show(mode == Mode.SAVE ? SAVE_SELECT : any ? LOAD_SELECT : NO_FILES);
				}
			}
			case NO_CARD, NO_FILES -> show(CARD_SELECT);
			case LOAD_SELECT -> {
				if (a == 0) {
					show(CARD_SELECT);
				} else if (a <= FILES && Files.exists(file(cardDir, a - 1))) {
					file = a - 1;
					show(LOAD_ASK);
				} else {
					show(LOAD_SELECT); // 빈 파일: 같은 화면을 다시 띄워 입력 잠금(EnableButton=2)을 푼다
				}
			}
			case SAVE_SELECT -> {
				if (a == 0) {
					show(CARD_SELECT);
				} else if (a <= FILES) {
					file = a - 1;
					show(Files.exists(file(cardDir, file)) ? OVERWRITE_ASK : CREATE_ASK);
				}
			}
			case LOAD_ASK -> busy(a == 1 ? LOADING : LOAD_SELECT);
			case OVERWRITE_ASK, CREATE_ASK -> busy(a == 1 ? SAVING : SAVE_SELECT);
			case LOAD_ERROR -> show(LOAD_SELECT);
			case SAVE_DONE -> finish(Result.SAVED);
			case SAVE_ERROR -> {
				fillSlots();
				show(SAVE_SELECT);
			}
			default -> {
			}
		}
	}

	/** 매 프레임: 진행 화면 시간 경과 → 실제 읽기/쓰기 → 다음 화면. */
	public void update(float dt) {
		if (result != Result.RUNNING || timer <= 0 || (timer -= dt) > 0) {
			return;
		}
		switch (state) {
			case LOADING -> {
				try {
					loaded = SaveGame.read(file(cardDir, file));
					busy(LOAD_DONE);
				} catch (IOException | RuntimeException ex) {
					System.out.println("불러오기 실패: " + ex);
					show(LOAD_ERROR);
				}
			}
			case LOAD_DONE -> finish(Result.LOADED);
			case SAVING -> {
				try {
					toSave.get().write(file(cardDir, file));
					show(SAVE_DONE);
				} catch (IOException | RuntimeException ex) {
					System.out.println("저장 실패: " + ex);
					show(SAVE_ERROR);
				}
			}
			default -> {
			}
		}
	}

	private void busy(int s) {
		show(s);
		if (s == LOADING || s == LOAD_DONE || s == SAVING) {
			timer = BUSY_SEC;
		}
	}

	private void show(int s) {
		state = s;
		timer = 0;
		vars.event("PS2_MemoryCard_Event", s);
	}

	private void finish(Result r) {
		result = r;
		state = 0;
		vars.event("PS2_MemoryCard_Event", 0);
	}

	/** 파일 목록 변수 (원작 Save_SlotText 0x22E810: 난이도 이름 + 플레이 시간 h:mm:ss). @return 파일이 하나라도 있으면 true. */
	private boolean fillSlots() {
		boolean any = false;
		for (int i = 0; i < FILES; i++) {
			Path f = file(cardDir, i);
			String name = msg(2, "비어있음"), date = "", time = "";
			if (Files.exists(f)) {
				try {
					SaveGame g = SaveGame.read(f);
					name = msg(3 + Math.max(0, Math.min(3, g.difficulty)), "난이도 " + g.difficulty);
					date = LocalDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), ZoneId.systemDefault()).format(DATE);
					time = playTime(g.playTime);
					any = true;
				} catch (IOException | RuntimeException ex) {
					name = "?";
				}
			}
			vars.set("PS2_MemoryCard_Name" + (i + 1), name);
			vars.set("PS2_MemoryCard_Date" + (i + 1), date);
			vars.set("PS2_MemoryCard_PlayTime" + (i + 1), time);
		}
		return any;
	}

	static String playTime(float sec) {
		int s = (int) Math.max(0, sec);
		return String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
	}

	private String msg(int id, String def) {
		String m = messages != null ? messages.get(id) : null;
		return m != null && !m.isEmpty() ? m : def;
	}
}
