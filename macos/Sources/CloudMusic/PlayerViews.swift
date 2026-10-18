import SwiftUI
import CloudMusicCore

struct PlayerBar:View {
    @EnvironmentObject private var model:LibraryModel
    @ObservedObject var player:PlayerModel
    @Environment(\.cm) private var p
    @State private var devices=false
    var body:some View {
        GeometryReader { geo in
            HStack(spacing:20) {
                Button{if player.current != nil{withAnimation(CMMotion.spring){model.showPlayer.toggle()}}}label:{
                    HStack(spacing:13) {
                        if let track=player.current{Cover(track:track,size:54,radius:11)}
                        else{Image(systemName:"music.note").font(.system(size:21,weight:.light)).foregroundStyle(p.muted).frame(width:54,height:54).background(p.raised,in:RoundedRectangle(cornerRadius:13))}
                        VStack(alignment:.leading,spacing:6) {
                            Text(player.current?.title ?? "Твоя музыка рядом").font(.system(size:12,weight:.semibold)).foregroundStyle(p.text).lineLimit(1)
                            Text(player.current?.artist ?? "Выбери первый трек").font(.system(size:11)).foregroundStyle(p.muted).lineLimit(1)
                        }
                        Spacer(minLength:0)
                    }.contentShape(Rectangle())
                }.buttonStyle(SoftPressStyle()).frame(width:min(260,max(180,geo.size.width*0.23))).accessibilityLabel("Открыть полный плеер")
                VStack(spacing:5){PlaybackControls(player:player);TransportProgress(player:player)}.frame(maxWidth:.infinity)
                HStack(spacing:3) {
                    Tool(icon:"list.bullet",label:"Очередь воспроизведения",active:model.showQueue){withAnimation(CMMotion.spring){model.showQueue.toggle()}}
                    Tool(icon:"hifispeaker",label:"Устройство воспроизведения"){devices.toggle()}.popover(isPresented:$devices){devicePopover}
                    Image(systemName:"speaker.wave.2").font(.system(size:11)).foregroundStyle(p.muted).padding(.leading,5)
                    Slider(value:$player.volume,in:0...1).controlSize(.mini).tint(p.green).frame(width:64).accessibilityLabel("Громкость")
                }.frame(width:177,alignment:.trailing)
            }.padding(.horizontal,19).frame(height:92).frame(maxWidth:.infinity)
                .background(p.panel,in:RoundedRectangle(cornerRadius:21)).overlay(RoundedRectangle(cornerRadius:21).strokeBorder(p.line,lineWidth:0.5))
        }.frame(height:92)
    }
    private var devicePopover:some View {
        VStack(alignment:.leading,spacing:16) {
            Text("Устройство воспроизведения").font(.system(size:15,weight:.semibold))
            Label("Этот Mac",systemImage:"laptopcomputer").foregroundStyle(p.green)
            Text("Используется системный аудиовыход.").font(.system(size:12)).foregroundStyle(p.muted)
            Button("Настройки звука…"){if let url=URL(string:"x-apple.systempreferences:com.apple.preference.sound"){NSWorkspace.shared.open(url)}}
        }.padding(24).frame(width:275).background(p.panel)
    }
}
private struct PlaybackControls:View {
    @ObservedObject var player:PlayerModel
    @Environment(\.cm) private var p
    var body:some View {
        HStack(spacing:17) {
            Tool(icon:"shuffle",label:"Случайный порядок",active:player.shuffle){player.shuffle.toggle()}
            Tool(icon:"backward.end.fill",label:"Предыдущий трек"){player.previous()}
            Button{player.toggle()}label:{
                ZStack {
                    Circle().fill(p.green).frame(width:36,height:36)
                    if player.isLoading{ProgressView().controlSize(.small).tint(p.onGreen)}
                    else{Image(systemName:player.isPlaying ? "pause.fill":"play.fill").font(.system(size:13,weight:.bold)).foregroundStyle(p.onGreen).offset(x:player.isPlaying ? 0:1)}
                }
            }.buttonStyle(SoftPressStyle()).accessibilityLabel(player.isPlaying ? "Пауза":"Воспроизвести")
            Tool(icon:"forward.end.fill",label:"Следующий трек"){player.next()}
            Tool(icon:player.repeatMode==1 ? "repeat.1":"repeat",label:"Повтор: \(player.repeatMode==0 ? "выключен":player.repeatMode==1 ? "один трек":"вся очередь")",active:player.repeatMode>0){player.repeatMode=(player.repeatMode+1)%3}
        }.disabled(player.current==nil).opacity(player.current==nil ? 0.35:1)
    }
}
// The playback clock invalidates only this small view, never the library or its cover grid.
private struct TransportProgress:View {
    let player:PlayerModel
    @ObservedObject private var timeline:PlaybackTimeline
    @Environment(\.cm) private var p
    @State private var editing=false
    @State private var draft=0.0
    init(player:PlayerModel){self.player=player;timeline=player.timeline}
    var body:some View {
        HStack(spacing:10) {
            Text(formatTime(editing ? draft:timeline.position)).frame(width:34,alignment:.trailing)
            Slider(value:Binding(get:{editing ? draft:min(timeline.position,max(timeline.duration,1))},set:{draft=$0;if !editing{player.seek($0)}}),in:0...max(timeline.duration,1),onEditingChanged:{active in
                if active{draft=timeline.position;editing=true}
                else{editing=false;player.seek(draft)}
            }).controlSize(.mini).tint(p.green).disabled(player.current==nil).accessibilityLabel("Позиция воспроизведения")
            Text(formatTime(timeline.duration)).frame(width:34,alignment:.leading)
        }.font(.system(size:10)).monospacedDigit().foregroundStyle(p.muted)
    }
}
struct QueuePanel:View {
    @EnvironmentObject private var model:LibraryModel
    @ObservedObject var player:PlayerModel
    @Environment(\.cm) private var p
    private var nextTracks:[(offset:Int,element:Track)]{Array(player.queue.enumerated()).filter{$0.offset>player.index}}
    var body:some View {
        VStack(alignment:.leading,spacing:20) {
            HStack {
                Text("Очередь").font(.system(size:15,weight:.semibold))
                Text("\(nextTracks.count)").font(.system(size:10,weight:.medium)).foregroundStyle(p.muted).padding(.horizontal,7).padding(.vertical,4).background(p.raised,in:Capsule())
                Spacer()
                Tool(icon:"sidebar.right",label:"Скрыть очередь"){withAnimation(CMMotion.spring){model.showQueue=false}}
            }
            if let track=player.current {
                if !model.showPlayer {
                    HStack(spacing:12) {
                        Cover(track:track,size:62,radius:11)
                        VStack(alignment:.leading,spacing:6) {
                            Text("СЕЙЧАС ИГРАЕТ").font(.system(size:8,weight:.semibold)).tracking(1.2).foregroundStyle(p.green)
                            Text(track.title).font(.system(size:12,weight:.semibold)).lineLimit(2)
                            Button(track.artist){withAnimation(CMMotion.page){model.openArtist(track.artist)}}.buttonStyle(.plain).font(.system(size:10)).foregroundStyle(p.muted)
                        }
                    }.padding(12).frame(maxWidth:.infinity,alignment:.leading).background(p.selected.opacity(0.45),in:RoundedRectangle(cornerRadius:15))
                }
                if nextTracks.isEmpty {
                    VStack(spacing:13) {
                        Image(systemName:"music.note.list").font(.system(size:28,weight:.ultraLight)).foregroundStyle(p.muted)
                        Text("Ты дошёл до конца очереди").font(.system(size:12,weight:.medium)).multilineTextAlignment(.center)
                        Text("Выбери другую коллекцию\nили включи повтор.").font(.system(size:11)).foregroundStyle(p.muted).multilineTextAlignment(.center).lineSpacing(4)
                    }.frame(maxWidth:.infinity,maxHeight:.infinity)
                } else {
                    Text("ДАЛЕЕ").font(.system(size:9,weight:.semibold)).tracking(1.5).foregroundStyle(p.muted)
                    ScrollView {
                        LazyVStack(spacing:5) {
                            ForEach(nextTracks,id:\.element.id){i,track in QueueTrackRow(track:track){player.jump(i)}.contextMenu{Button("Убрать из очереди"){player.removeFromQueue(i)}}}
                        }
                    }
                }
                Spacer(minLength:0)
                SourceBadge(title:model.downloaded(track) ? "Скачано на Mac":track.provider.label,icon:model.downloaded(track) ? "checkmark.circle":"waveform")
            } else {
                Spacer()
                Image(systemName:"music.note.list").font(.system(size:32,weight:.ultraLight)).foregroundStyle(p.muted).frame(maxWidth:.infinity)
                Text("Здесь появится твоя очередь").font(.system(size:12)).foregroundStyle(p.muted).multilineTextAlignment(.center).frame(maxWidth:.infinity)
                Spacer()
            }
        }.foregroundStyle(p.text).padding(18).frame(width:250).frame(maxHeight:.infinity).background(p.sidebar,in:RoundedRectangle(cornerRadius:21))
    }
}
private struct QueueTrackRow:View {
    @Environment(\.cm) private var p
    let track:Track;let action:()->Void
    var body:some View {
        Button(action:action){HStack(spacing:11) {
            Cover(track:track,size:39,radius:8)
            VStack(alignment:.leading,spacing:5) {
                Text(track.title).font(.system(size:11,weight:.medium)).foregroundStyle(p.text).lineLimit(1)
                Text(track.artist).font(.system(size:10)).foregroundStyle(p.muted).lineLimit(1)
            }
            Spacer(minLength:0)
        }.padding(6).contentShape(RoundedRectangle(cornerRadius:10))}
            .buttonStyle(IconPressStyle()).accessibilityLabel("Далее: \(track.title)")
    }
}
struct FullPlayerView:View {
    @EnvironmentObject private var model:LibraryModel
    @ObservedObject var player:PlayerModel
    @Environment(\.cm) private var p
    var body:some View {
        GeometryReader { geo in
            if let track=player.current {
                VStack(spacing:0) {
                    HStack {
                        Button{withAnimation(CMMotion.spring){model.showPlayer=false}}label:{Label("Медиатека",systemImage:"chevron.left").font(.system(size:12,weight:.medium))}.buttonStyle(SoftPressStyle())
                        Spacer()
                        Text("СЕЙЧАС ИГРАЕТ").font(.system(size:9,weight:.semibold)).tracking(1.8)
                        Spacer()
                        Menu{TrackActions(track:track)}label:{Image(systemName:"ellipsis").frame(width:25,height:25)}.menuStyle(.borderlessButton).fixedSize().accessibilityLabel("Действия с текущим треком")
                    }.foregroundStyle(p.muted).padding(.horizontal,30).padding(.vertical,20)
                    ScrollView {
                        VStack(alignment:.leading,spacing:32) {
                            hero(track,width:geo.size.width).id(track.id).transition(.opacity)
                            if !nextTracks.isEmpty {
                                VStack(alignment:.leading,spacing:14) {
                                    Text("Далее в твоей очереди").font(.system(size:15,weight:.semibold)).foregroundStyle(p.text)
                                    HStack(spacing:10){ForEach(nextTracks,id:\.element.id){i,next in QueueTrackRow(track:next){player.jump(i)}.padding(5).background(p.panel.opacity(0.75),in:RoundedRectangle(cornerRadius:13))}}
                                }
                            }
                        }.padding(32).frame(maxWidth:1040).frame(maxWidth:.infinity,minHeight:max(0,geo.size.height-67),alignment:.center)
                    }
                }.background(LinearGradient(colors:[p.selected.opacity(0.23),p.bg,p.bg],startPoint:.topLeading,endPoint:.bottomTrailing))
            }
        }
    }
    private var nextTracks:[(offset:Int,element:Track)]{Array(Array(player.queue.enumerated()).filter{$0.offset>player.index}.prefix(2))}
    @ViewBuilder private func hero(_ track:Track,width:CGFloat)->some View {
        if width>=740 {
            HStack(alignment:.center,spacing:34) {
                heroCover(track,size:min(320,max(240,width*0.43)))
                details(track).frame(maxWidth:.infinity,alignment:.leading)
            }
        } else {
            VStack(spacing:27){heroCover(track,size:min(300,max(210,width-140)));details(track)}
        }
    }
    private func heroCover(_ track:Track,size:CGFloat)->some View {
        Cover(track:track,size:size,radius:20).shadow(color:.black.opacity(p.dark ? 0.3:0.13),radius:22,y:12)
    }
    private func details(_ track:Track)->some View {
        VStack(alignment:.leading,spacing:19) {
            SourceBadge(title:model.downloaded(track) ? "Скачано на Mac":track.provider.label,icon:model.downloaded(track) ? "checkmark.circle":"cloud")
            Text(track.title).font(.system(size:34,weight:.semibold)).tracking(-1.2).foregroundStyle(p.text).lineLimit(3).fixedSize(horizontal:false,vertical:true)
            Button{withAnimation(CMMotion.page){model.openArtist(track.artist)}}label:{Text(track.artist).font(.system(size:17,weight:.medium)).foregroundStyle(p.muted)}.buttonStyle(SoftPressStyle())
            Text(track.album).font(.system(size:12)).foregroundStyle(p.muted.opacity(0.8)).lineLimit(2)
            HStack(spacing:12) {
                Tool(icon:model.library.favorites.contains(track.id) ? "heart.fill":"heart",label:"Избранное",active:model.library.favorites.contains(track.id)){model.toggleFavorite(track)}
                Button{model.openArtist(track.artist)}label:{Label("К исполнителю",systemImage:"person.crop.circle").font(.system(size:11,weight:.medium))}.buttonStyle(SoftPressStyle()).foregroundStyle(p.muted)
                if model.downloaded(track){Tool(icon:"checkmark.circle.fill",label:"Скачано на Mac",active:true){model.reveal(track)}}
                else if track.provider == .yandex || track.provider == .google{Tool(icon:"arrow.down.to.line",label:"Скачать на Mac"){model.download(track)}}
            }.padding(.top,2)
            if let state=model.downloadStates[track.id],state.active {
                HStack(spacing:10) {
                    if let progress=state.fraction{ProgressView(value:progress).frame(width:90).tint(p.green)}else{ProgressView().controlSize(.small)}
                    Text(state.label).font(.system(size:10)).foregroundStyle(p.muted)
                    Button("Отменить"){model.cancelDownload(track)}.buttonStyle(.plain).font(.system(size:10))
                }
            }
        }
    }
}
