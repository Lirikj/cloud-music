// swift-tools-version: 5.10
import PackageDescription
let package = Package(
    name: "CloudMusic", platforms: [.macOS(.v13)],
    products: [.executable(name: "CloudMusic", targets: ["CloudMusic"]), .executable(name: "cloud-music-check", targets: ["CloudMusicCheck"])],
    targets: [
        .target(name: "CloudMusicCore"),
        .executableTarget(name: "CloudMusic", dependencies: ["CloudMusicCore"], linkerSettings: [.linkedFramework("AppKit"), .linkedFramework("AVFoundation"), .linkedFramework("MediaPlayer")]),
        .executableTarget(name: "CloudMusicCheck", dependencies: ["CloudMusicCore"], linkerSettings: [.linkedFramework("AVFoundation")])
    ]
)
