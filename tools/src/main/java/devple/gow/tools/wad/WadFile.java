package devple.gow.tools.wad;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** WAD 태그 스트림 파서. */
public final class WadFile {

	private final String name;
	private final ByteBuffer buf;
	private final List<WadTag> tags;

	public WadFile(String name, ByteBuffer raw) {
		this.name = name;
		this.buf = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		this.tags = Collections.unmodifiableList(parse());
	}

	/** 미리 만든 파생 해석 (키 → 값, 한 번 꺼내면 끝) — 적재 스레드가 GL 없는 해석을 미리 붙여 둔다 (엔진 48 문서 §9). WAD 와 수명이 같다. */
	private final java.util.Map<Object, Object> derived = new java.util.concurrent.ConcurrentHashMap<>();

	public void putDerived(Object key, Object value) {
		derived.put(key, value);
	}

	/** 붙여 둔 파생 해석을 꺼낸다 (없으면 null). */
	public Object takeDerived(Object key) {
		return derived.remove(key);
	}

	public static WadFile load(Path p) throws IOException {
		return new WadFile(p.getFileName().toString(), ByteBuffer.wrap(Files.readAllBytes(p)));
	}

	private List<WadTag> parse() {
		List<WadTag> out = new ArrayList<>();
		Deque<WadTag> parents = new ArrayDeque<>();
		WadTag last = null;
		int depth = 0;
		int off = 0;
		while (off + WadTag.HEADER <= buf.limit()) {
			int tag = Short.toUnsignedInt(buf.getShort(off));
			int flags = Short.toUnsignedInt(buf.getShort(off + 2));
			int size = buf.getInt(off + 4);
			String nm = cstr(buf, off + 8, 24);
			if (tag == WadTagType.GROUP_END) {
				depth--;
				parents.poll();
			}
			WadTag t = new WadTag(off, tag, flags, size, nm, depth, parents.peek(), buf);
			out.add(t);
			if (tag == WadTagType.GROUP_START) {
				depth++;
				parents.push(last);
			} else if (tag != WadTagType.GROUP_END) {
				last = t;
			}
			off = t.nextOffset();
		}
		return out;
	}

	public String name() {
		return name;
	}

	public List<WadTag> tags() {
		return tags;
	}

	public List<WadTag> resources() {
		return tags.stream().filter(t -> t.tag() == WadTagType.RESOURCE && t.hasData()).toList();
	}

	public Optional<WadTag> find(String resName) {
		return tags.stream().filter(t -> t.name().equals(resName) && t.hasData()).findFirst();
	}

	static String cstr(ByteBuffer b, int off, int max) {
		int len = 0;
		while (len < max && b.get(off + len) != 0) {
			len++;
		}
		byte[] tmp = new byte[len];
		b.get(off, tmp);
		return new String(tmp, StandardCharsets.ISO_8859_1);
	}
}
