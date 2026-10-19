package devple.gow.engine.ui;

import devple.gow.tools.ui.FlpFile;

import java.util.Map;
import java.util.TreeMap;

/**
 * 표시 목록 노드 (플래시 DisplayObject 대응).
 * MOVIE = 타임라인을 가진 루트/무비클립(Data7)/입력 스프라이트(Data6), MESH = 메시 참조, TEXT = 동적 라벨.
 * 동일성은 (핸들러, 같은 핸들러가 연속된 키 구간의 시작 키) — 구간이 바뀌면 새 인스턴스(프레임 0부터).
 */
final class FlpNode {

	enum Kind { MOVIE, MESH, TEXT }

	final Kind kind;
	final int handler;
	final int segment;
	final FlpNode parent;
	String name;

	/** 로컬 변환: x' = a·x + c·y + tx, y' = b·x + d·y + ty (플래시 MATRIX 순서). */
	float a = 1, b, c, d = 1, tx, ty;
	/** 컬러 변환 곱 (1.0 = 원본). */
	float cr = 1, cg = 1, cb = 1, ca = 1;

	// MOVIE
	final FlpFile.Timeline timeline;
	final FlpFile.Sprite sprite;
	FlpNode[] slots;
	int frame = -1;
	boolean playing = true;
	final Map<String, String> vars;

	// MESH / TEXT
	final int index;

	boolean removed;

	FlpNode(Kind kind, int handler, int segment, FlpNode parent, FlpFile.Timeline timeline, FlpFile.Sprite sprite, int index) {
		this.kind = kind;
		this.handler = handler;
		this.segment = segment;
		this.parent = parent;
		this.timeline = timeline;
		this.sprite = sprite;
		this.index = index;
		if (kind == Kind.MOVIE) {
			slots = new FlpNode[timeline.layers().size()];
			vars = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			// 버튼(입력 스프라이트)은 상태 프레임을 재생하지 않는다
			playing = sprite == null;
		} else {
			vars = null;
		}
	}

	int totalFrames() {
		return Math.max(1, timeline.totalFrames());
	}

	/** 이름으로 자식 찾기 (대소문자 무시). */
	FlpNode child(String n) {
		if (slots == null) {
			return null;
		}
		for (FlpNode s : slots) {
			if (s != null && s.name != null && s.name.equalsIgnoreCase(n)) {
				return s;
			}
		}
		return null;
	}

	void markRemoved() {
		removed = true;
		if (slots != null) {
			for (FlpNode s : slots) {
				if (s != null) {
					s.markRemoved();
				}
			}
		}
	}

	String path() {
		if (parent == null) {
			return "_level0";
		}
		return parent.path() + "/" + (name != null ? name : "#" + handler);
	}

	@Override
	public String toString() {
		return kind + " " + path() + (kind == Kind.MOVIE ? " @" + frame + (playing ? " play" : " stop") : "");
	}
}
