// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TiltFold",
    platforms: [
        .iOS(.v16),
    ],
    products: [
        .library(name: "TiltFold", targets: ["TiltFold"]),
    ],
    targets: [
        .target(name: "TiltFold"),
        .testTarget(name: "TiltFoldTests", dependencies: ["TiltFold"]),
    ]
)
