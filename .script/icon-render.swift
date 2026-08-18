// Renders an Icon Composer document (.icon) into a flat PNG.
//
// macOS composes the Liquid-Glass look - squircle, refraction, specular rim,
// drop shadow - inside the system icon renderer; there is no supported CLI
// that hands that render back. QuickLook has one: the .icon thumbnail IS the
// composed icon, at whatever size we ask for. That is the whole trick behind
// build-icons.py, which drives this helper.
//
// Compiled on demand by build-icons.py, never shipped.

import Foundation
import QuickLookThumbnailing
import AppKit

let args = CommandLine.arguments
guard args.count >= 4, let size = Double(args[3]) else {
    FileHandle.standardError.write("usage: icon-render <in.icon> <out.png> <size>\n".data(using: .utf8)!)
    exit(2)
}

let request = QLThumbnailGenerator.Request(
    fileAt: URL(fileURLWithPath: args[1]),
    size: CGSize(width: size, height: size),
    scale: 1.0,
    representationTypes: .thumbnail)

var status: Int32 = 1
let done = DispatchSemaphore(value: 0)

QLThumbnailGenerator.shared.generateBestRepresentation(for: request) { rep, error in
    defer { done.signal() }
    guard let image = rep?.cgImage else {
        FileHandle.standardError.write("render failed: \(String(describing: error))\n".data(using: .utf8)!)
        return
    }
    let bitmap = NSBitmapImageRep(cgImage: image)
    guard let png = bitmap.representation(using: .png, properties: [:]) else { return }
    do {
        try png.write(to: URL(fileURLWithPath: args[2]))
        print("\(image.width)x\(image.height)")
        status = 0
    } catch {
        FileHandle.standardError.write("write failed: \(error)\n".data(using: .utf8)!)
    }
}

// The generator answers on a background queue; 120 s is far past any real
// render and only ever trips when the QuickLook extension is wedged.
if done.wait(timeout: .now() + 120) == .timedOut {
    FileHandle.standardError.write("render timed out\n".data(using: .utf8)!)
    exit(1)
}
exit(status)
