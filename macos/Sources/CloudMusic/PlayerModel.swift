import Foundation
import AVFoundation
import MediaPlayer
import Combine
import CloudMusicCore

@MainActor final class PlaybackTimeline:ObservableObject {
    @Published private(set) var position=0.0
    @Published private(set) var duration=0.0
    func setPosition(_ value:Double){if abs(position-value)>0.01{position=value}}
    func setDuration(_ value:Double){if abs(duration-value)>0.01{duration=value}}
}

@MainActor final class PlayerModel:ObservableObject {
    @Published private(set) var queue:[Track]=[];@Published private(set) var index=0
    @Published private(set) var isPlaying=false;@Published private(set) var isLoading=false
    let timeline=PlaybackTimeline()
    var position:Double{get{timeline.position}set{timeline.setPosition(newValue)}}
    var duration:Double{get{timeline.duration}set{timeline.setDuration(newValue)}}
    @Published var error:String?;@Published var shuffle=false {didSet{order=PlaybackOrder(count:queue.count,current:index,shuffle:shuffle)}};@Published var repeatMode=0
    @Published var volume:Float=0.7 {didSet{player.volume=volume}}
    var current:Track?{queue.indices.contains(index) ? queue[index] : nil}
    var resolve:((Track,Bool) async throws -> URL)?;var priority:((Bool)->Void)?
    private let player=AVPlayer();private var observations:[NSKeyValueObservation]=[];private var timeObserver:Any?;private var notifications:[NSObjectProtocol]=[]
    private var task:Task<Void,Never>?;private var generation=UUID();private var desiredPlaying=false;private var retried=false;private var scopeURL:URL?;private var scopeActive=false;private var order=PlaybackOrder(count:0)
    init(){
        player.automaticallyWaitsToMinimizeStalling=false;player.volume=volume
        observations.append(player.observe(\.timeControlStatus,options:[.initial,.new]){[weak self] p,_ in Task{@MainActor [weak self] in self?.updateStatus(p.timeControlStatus)}})
        timeObserver=player.addPeriodicTimeObserver(forInterval:CMTime(seconds:0.25,preferredTimescale:600),queue:.main){[weak self] t in Task{@MainActor [weak self] in self?.tick(t)}}
        let commands=MPRemoteCommandCenter.shared()
        commands.playCommand.addTarget{[weak self] _ in Task{@MainActor [weak self] in self?.resume()};return .success}
        commands.pauseCommand.addTarget{[weak self] _ in Task{@MainActor [weak self] in self?.pause()};return .success}
        commands.togglePlayPauseCommand.addTarget{[weak self] _ in Task{@MainActor [weak self] in self?.toggle()};return .success}
        commands.nextTrackCommand.addTarget{[weak self] _ in Task{@MainActor [weak self] in self?.next()};return .success}
        commands.previousTrackCommand.addTarget{[weak self] _ in Task{@MainActor [weak self] in self?.previous()};return .success}
        commands.changePlaybackPositionCommand.addTarget{[weak self] e in guard let e=e as? MPChangePlaybackPositionCommandEvent else{return .commandFailed};Task{@MainActor [weak self] in self?.seek(e.positionTime)};return .success}
    }
    func play(_ track:Track,in tracks:[Track]){
        queue=tracks.isEmpty ? [track] : tracks;index=queue.firstIndex(where:{$0.id==track.id}) ?? 0;order=PlaybackOrder(count:queue.count,current:index,shuffle:shuffle);load(fresh:false,at:0)
    }
    private func load(fresh:Bool,at seconds:Double){
        guard let track=current else{return};task?.cancel();generation=UUID();let token=generation;error=nil;isLoading=true;isPlaying=false;desiredPlaying=true;position=seconds;duration=track.duration;retried=fresh;priority?(true)
        player.pause();player.replaceCurrentItem(with:nil);releaseScope();clearItemObservers()
        task=Task { [weak self] in
            guard let self else{return}
            do {
                guard let resolve=self.resolve else{throw MusicError("Не удалось открыть трек.")}
                let url=try await resolve(track,fresh);try Task.checkCancellation();guard self.generation==token else{return}
                if url.isFileURL {self.scopeURL=url;self.scopeActive=url.startAccessingSecurityScopedResource()}
                let item=AVPlayerItem(url:url);item.preferredForwardBufferDuration=8;item.canUseNetworkResourcesForLiveStreamingWhilePaused=false
                self.observations.append(item.observe(\.status,options:[.new]){[weak self] item,_ in Task{@MainActor [weak self] in self?.itemStatus(item,token:token,seek:seconds)}})
                self.notifications.append(NotificationCenter.default.addObserver(forName:.AVPlayerItemDidPlayToEndTime,object:item,queue:.main){[weak self] _ in Task{@MainActor [weak self] in self?.ended(token)}})
                self.notifications.append(NotificationCenter.default.addObserver(forName:.AVPlayerItemPlaybackStalled,object:item,queue:.main){[weak self] _ in Task{@MainActor [weak self] in guard let self,self.generation==token,self.desiredPlaying else{return};self.isLoading=true;self.priority?(true);self.player.play()}})
                self.player.replaceCurrentItem(with:item);if self.desiredPlaying{self.player.play()};self.nowPlaying()
            }catch{guard !Task.isCancelled,self.generation==token else{return};self.fail(error)}
        }
    }
    private func clearItemObservers(){if observations.count>1{observations.removeSubrange(1...)};notifications.forEach{NotificationCenter.default.removeObserver($0)};notifications.removeAll()}
    private func releaseScope(){if scopeActive{scopeURL?.stopAccessingSecurityScopedResource()};scopeURL=nil;scopeActive=false}
    private func itemStatus(_ item:AVPlayerItem,token:UUID,seek:Double){
        guard generation==token else{return}
        if item.status == .readyToPlay {let s=item.duration.seconds;if s.isFinite && s>0{duration=s};if seek>0{self.seek(seek)};if desiredPlaying{player.play()};nowPlaying()}
        else if item.status == .failed {
            if !retried,current?.provider == .yandex || !retried && current?.provider == .google {load(fresh:true,at:position)}
            else{fail(MusicError("Не удалось воспроизвести файл. Проверь сеть, доступ и поддержку формата macOS."))}
        }
    }
    private func fail(_ e:Error){error=e.localizedDescription;isLoading=false;isPlaying=false;desiredPlaying=false;priority?(false);nowPlaying()}
    private func updateStatus(_ s:AVPlayer.TimeControlStatus){let playing=s == .playing;if isPlaying != playing{isPlaying=playing};if playing{if isLoading{isLoading=false};priority?(false)}else if s == .waitingToPlayAtSpecifiedRate && desiredPlaying{if !isLoading{isLoading=true};priority?(true)};nowPlaying()}
    private func tick(_ t:CMTime){if t.seconds.isFinite{position=t.seconds};if let d=player.currentItem?.duration.seconds,d.isFinite,d>0{duration=d};if desiredPlaying && !isPlaying && player.currentItem?.status == .readyToPlay && player.currentItem?.isPlaybackBufferEmpty==false{player.play()};}
    private func ended(_ token:UUID){guard token==generation else{return};if repeatMode==1{seek(0);resume()}else{next(automatic:true)}}
    func toggle(){if desiredPlaying{pause()}else{resume()}}
    func pause(){desiredPlaying=false;player.pause();isLoading=false;priority?(false);nowPlaying()}
    func resume(){guard current != nil else{return};if player.currentItem==nil || error != nil{load(fresh:error != nil,at:position)}else{desiredPlaying=true;isLoading = player.currentItem?.status != .readyToPlay;player.play();nowPlaying()}}
    func seek(_ seconds:Double){let target=max(0,min(seconds,duration>0 ? duration : seconds));position=target;player.seek(to:CMTime(seconds:target,preferredTimescale:600),toleranceBefore:.zero,toleranceAfter:CMTime(seconds:0.1,preferredTimescale:600));nowPlaying()}
    func next(automatic:Bool=false){
        guard !queue.isEmpty else{return}
        guard let next=order.next(index,wrap:repeatMode==2 || !automatic) else{pause();return}
        index=next
        load(fresh:false,at:0)
    }
    func previous(){if position>3{seek(0)}else if !queue.isEmpty{index=order.previous(index) ?? 0;load(fresh:false,at:0)}}
    func jump(_ index:Int){guard queue.indices.contains(index) else{return};self.index=index;load(fresh:false,at:0)}
    func removeFromQueue(_ i:Int){guard queue.indices.contains(i) else{return};if i==index{if queue.count==1{stop();return};queue.remove(at:i);index=min(i,queue.count-1);order=PlaybackOrder(count:queue.count,current:index,shuffle:shuffle);load(fresh:false,at:0)}else{queue.remove(at:i);if i<index{index-=1};order=PlaybackOrder(count:queue.count,current:index,shuffle:shuffle)}}
    func stop(){task?.cancel();generation=UUID();player.pause();player.replaceCurrentItem(with:nil);releaseScope();clearItemObservers();queue=[];index=0;position=0;duration=0;isPlaying=false;isLoading=false;desiredPlaying=false;priority?(false);MPNowPlayingInfoCenter.default().nowPlayingInfo=nil}
    private func nowPlaying(){guard let track=current else{return};MPNowPlayingInfoCenter.default().nowPlayingInfo=[MPMediaItemPropertyTitle:track.title,MPMediaItemPropertyArtist:track.artist,MPMediaItemPropertyAlbumTitle:track.album,MPMediaItemPropertyPlaybackDuration:duration,MPNowPlayingInfoPropertyElapsedPlaybackTime:position,MPNowPlayingInfoPropertyPlaybackRate:isPlaying ? 1:0];MPNowPlayingInfoCenter.default().playbackState=isPlaying ? .playing : .paused}
}
