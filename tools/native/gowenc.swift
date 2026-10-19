// gowenc — devple-gow 컷신 내보내기 도우미 (macOS AVFoundation / VideoToolbox 하드웨어 H.264).
// Java(video:export)가 PSS 를 디코딩해 I420 프레임을 stdin 으로 보내고, 오디오는 WAV 로 넘긴다.
// 사용: gowenc <width> <height> <fpsNum> <fpsDen> <parH> <parV> <audio.wav|-> <out.mov>
import AVFoundation
import CoreVideo
import Foundation

func fail(_ m: String) -> Never {
	FileHandle.standardError.write((m + "\n").data(using: .utf8)!)
	exit(1)
}

let a = CommandLine.arguments
if a.count != 9 { fail("usage: gowenc w h fpsNum fpsDen parH parV audio.wav|- out.mov") }
let w = Int(a[1])!, h = Int(a[2])!
let fpsNum = Int32(a[3])!, fpsDen = Int64(a[4])!
let parH = Int(a[5])!, parV = Int(a[6])!
let wav = a[7], out = URL(fileURLWithPath: a[8])
let tmp = URL(fileURLWithPath: a[8] + ".video.mov")
try? FileManager.default.removeItem(at: tmp)
try? FileManager.default.removeItem(at: out)

// 1) 영상: I420 → NV12 픽셀 버퍼 → H.264 (하드웨어)
guard let writer = try? AVAssetWriter(outputURL: tmp, fileType: .mov) else { fail("writer") }
let settings: [String: Any] = [
	AVVideoCodecKey: AVVideoCodecType.h264,
	AVVideoWidthKey: w,
	AVVideoHeightKey: h,
	AVVideoPixelAspectRatioKey: [AVVideoPixelAspectRatioHorizontalSpacingKey: parH,
	                             AVVideoPixelAspectRatioVerticalSpacingKey: parV],
	AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 4_000_000,
	                                  AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel],
]
let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
	kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
	kCVPixelBufferWidthKey as String: w, kCVPixelBufferHeightKey as String: h,
])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

let frameBytes = w * h * 3 / 2
let stdin = FileHandle.standardInput
var frame: Int64 = 0
while true {
	var buf = Data()
	while buf.count < frameBytes {
		let d = stdin.readData(ofLength: frameBytes - buf.count)
		if d.isEmpty { break }
		buf.append(d)
	}
	if buf.count < frameBytes { break }
	while !input.isReadyForMoreMediaData { usleep(1000) }
	var pb: CVPixelBuffer?
	CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
	guard let p = pb else { fail("pixel buffer") }
	CVPixelBufferLockBaseAddress(p, [])
	buf.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
		let s = src.bindMemory(to: UInt8.self)
		let yDst = CVPixelBufferGetBaseAddressOfPlane(p, 0)!.assumingMemoryBound(to: UInt8.self)
		let yStride = CVPixelBufferGetBytesPerRowOfPlane(p, 0)
		for r in 0..<h { memcpy(yDst + r * yStride, s.baseAddress! + r * w, w) }
		let uvDst = CVPixelBufferGetBaseAddressOfPlane(p, 1)!.assumingMemoryBound(to: UInt8.self)
		let uvStride = CVPixelBufferGetBytesPerRowOfPlane(p, 1)
		let cw = w / 2, ch = h / 2
		let uOff = w * h, vOff = w * h + cw * ch
		for r in 0..<ch {
			for c in 0..<cw {
				uvDst[r * uvStride + 2 * c] = s[uOff + r * cw + c]
				uvDst[r * uvStride + 2 * c + 1] = s[vOff + r * cw + c]
			}
		}
	}
	CVPixelBufferUnlockBaseAddress(p, [])
	adaptor.append(p, withPresentationTime: CMTime(value: frame * fpsDen, timescale: fpsNum))
	frame += 1
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
if writer.status != .completed { fail("video write: \(String(describing: writer.error))") }

// 2) 오디오 결합: 영상 트랙 + WAV → MOV (패스스루, PCM 그대로)
if wav == "-" {
	try! FileManager.default.moveItem(at: tmp, to: out)
	print("frames=\(frame)")
	exit(0)
}
let comp = AVMutableComposition()
let vAsset = AVURLAsset(url: tmp)
let aAsset = AVURLAsset(url: URL(fileURLWithPath: wav))
let sem = DispatchSemaphore(value: 0)
Task {
	do {
		let vTracks = try await vAsset.loadTracks(withMediaType: .video)
		let aTracks = try await aAsset.loadTracks(withMediaType: .audio)
		let vDur = try await vAsset.load(.duration)
		let aDur = try await aAsset.load(.duration)
		let vt = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
		try vt.insertTimeRange(CMTimeRange(start: .zero, duration: vDur), of: vTracks[0], at: .zero)
		if let at = aTracks.first {
			let ct = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
			try ct.insertTimeRange(CMTimeRange(start: .zero, duration: aDur), of: at, at: .zero)
		}
		let ex = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetPassthrough)!
		try await ex.export(to: out, as: .mov)
		print("frames=\(frame)")
	} catch {
		FileHandle.standardError.write("export: \(error)\n".data(using: .utf8)!)
		exit(1)
	}
	sem.signal()
}
sem.wait()
try? FileManager.default.removeItem(at: tmp)
