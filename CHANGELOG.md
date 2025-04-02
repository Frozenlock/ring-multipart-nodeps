# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- Updated the :progress-fn signature to match Ring's original middleware
- The progress function now takes four parameters: request, bytes-read, content-length, and item-count

## [1.0.0] - 2025-04-01
- Initial release of ring-multipart-nodeps
- Zero-dependency replacement for ring.middleware.multipart-params
- Compatible with both JVM Clojure and Babashka
