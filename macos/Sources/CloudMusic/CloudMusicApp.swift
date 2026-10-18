import SwiftUI
import AppKit
import CloudMusicCore

@main struct CloudMusicApp:App {
    @StateObject private var model=LibraryModel()
    @NSApplicationDelegateAdaptor(MusicDelegate.self) private var delegate
    var body:some Scene {
        WindowGroup("Cloud Music") {
            RootView(model:model,player:model.player).environmentObject(model).environmentObject(model.artwork)
                .preferredColorScheme(model.appearance=="system" ? nil:model.appearance=="light" ? .light:.dark)
                .onAppear{delegate.model=model}
                .onOpenURL{url in if url.isFileURL{model.importURLs([url])}else{model.connect(url.absoluteString)}}
                .onReceive(NotificationCenter.default.publisher(for:NSApplication.didBecomeActiveNotification)){_ in model.refreshFileAvailability()}
        }.windowStyle(.hiddenTitleBar).defaultSize(width:1280,height:850).windowResizability(.contentMinSize)
            .commands {
                CommandGroup(after:.newItem) {
                    Button("Подключить облачную папку…"){model.showConnect=true}.keyboardShortcut("n",modifiers:[.command,.shift])
                    Button("Добавить музыку с Mac…"){model.chooseLocal()}.keyboardShortcut("o")
                    Divider()
                    Button("Поиск"){NotificationCenter.default.post(name:.focusMusicSearch,object:nil)}.keyboardShortcut("k")
                }
                CommandMenu("Воспроизведение") {
                    Button(model.player.isPlaying ? "Пауза":"Воспроизвести"){model.player.toggle()}.keyboardShortcut(" ",modifiers:[])
                    Button("Следующий трек"){model.player.next()}.keyboardShortcut(.rightArrow,modifiers:.command)
                    Button("Предыдущий трек"){model.player.previous()}.keyboardShortcut(.leftArrow,modifiers:.command)
                }
            }
        Settings {
            ThemedSettingsView().environmentObject(model).environmentObject(model.artwork)
                .preferredColorScheme(model.appearance=="system" ? nil:model.appearance=="light" ? .light:.dark)
        }
    }
}
extension Notification.Name{static let focusMusicSearch=Notification.Name("CloudMusicFocusSearch")}

struct RootView:View {
    @ObservedObject var model:LibraryModel
    @ObservedObject var player:PlayerModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var searching:Bool
    @Namespace private var navigation
    private var p:Palette{Palette(dark:scheme == .dark)}
    private var routeKey:String{model.showPlayer && player.current != nil ? "player":model.selected+"|"+(model.artist ?? "")+"|"+(model.albumID ?? "")}
    var body:some View {
        GeometryReader { geo in
            VStack(spacing:10) {
                HStack{Spacer();Text("Cloud Music").font(.system(size:10,weight:.medium)).foregroundStyle(p.muted.opacity(0.7));Spacer()}.frame(height:21)
                HStack(spacing:10) {
                    sidebar
                    VStack(spacing:0) {
                        toolbar
                        Group {
                            if !model.isReady{ProgressView().controlSize(.small).frame(maxWidth:.infinity,maxHeight:.infinity)}
                            else if model.library.tracks.isEmpty{WelcomeView()}
                            else if model.showPlayer && player.current != nil{FullPlayerView(player:player)}
                            else{CollectionView(player:player)}
                        }.id(routeKey).transition(.opacity.combined(with:.offset(y:reduceMotion ? 0:7)))
                            .frame(maxWidth:.infinity,maxHeight:.infinity)
                    }.background(p.bg,in:RoundedRectangle(cornerRadius:21)).clipShape(RoundedRectangle(cornerRadius:21))
                    if model.showQueue && geo.size.width>=1190 && player.current != nil {
                        QueuePanel(player:player).transition(.move(edge:.trailing).combined(with:.opacity))
                    }
                }.frame(maxHeight:.infinity)
                if model.busy{importProgress.transition(.move(edge:.bottom).combined(with:.opacity))}
                PlayerBar(player:player)
            }.padding(.horizontal,12).padding(.bottom,12).padding(.top,2)
                .background(p.top).environment(\.cm,p)
                .animation(reduceMotion ? nil:CMMotion.page,value:routeKey)
                .animation(reduceMotion ? nil:CMMotion.spring,value:model.showQueue)
                .animation(reduceMotion ? nil:CMMotion.page,value:player.current != nil)
                .animation(reduceMotion ? nil:CMMotion.quick,value:model.busy)
                .animation(reduceMotion ? nil:.easeInOut(duration:0.25),value:model.appearance)
                .overlay(alignment:.bottom){if let status=model.status{toast(status).transition(.move(edge:.bottom).combined(with:.opacity))}}
                .animation(reduceMotion ? nil:CMMotion.quick,value:model.status)
                .sheet(isPresented:$model.showConnect){ConnectView().environmentObject(model).environment(\.cm,p)}
                .alert("Cloud Music",isPresented:Binding(get:{model.alert != nil || player.error != nil},set:{if !$0{model.alert=nil;player.error=nil}})) {
                    if player.error != nil{Button("Повторить"){player.resume()}}
                    Button("Понятно",role:.cancel){model.alert=nil;player.error=nil}
                } message:{Text(model.alert ?? player.error ?? "")}
                .onReceive(NotificationCenter.default.publisher(for:.focusMusicSearch)){_ in searching=true}
                .task(id:model.status) {
                    guard let status=model.status else{return}
                    do{try await Task.sleep(nanoseconds:4_000_000_000);if model.status==status{model.status=nil}}catch{}
                }
                .transaction{if reduceMotion{$0.animation=nil}}
                .background(WindowSetup())
        }.frame(minWidth:1020,minHeight:700)
    }
    private var importProgress:some View {
        HStack(spacing:11) {
            ProgressView().controlSize(.small)
            Text(model.busyLabel).font(.system(size:11))
            Spacer()
            Button("Отменить"){model.cancelImport()}.buttonStyle(.plain).font(.system(size:11))
        }.foregroundStyle(p.muted).padding(.horizontal,18).padding(.vertical,9).background(p.panel,in:RoundedRectangle(cornerRadius:12))
    }
    private func toast(_ status:String)->some View {
        Label(status,systemImage:"checkmark.circle.fill").font(.system(size:12)).foregroundStyle(p.text)
            .padding(.horizontal,18).padding(.vertical,13).background(p.raised,in:Capsule()).overlay(Capsule().strokeBorder(p.line,lineWidth:0.5))
            .shadow(color:.black.opacity(0.15),radius:12,y:4).padding(.bottom,117).allowsHitTesting(false)
    }
    private var toolbar:some View {
        HStack(spacing:12) {
            Tool(icon:"chevron.left",label:"Назад к медиатеке"){withAnimation(CMMotion.page){model.navigate("library")}}
            HStack(spacing:9) {
                Image(systemName:"magnifyingglass").font(.system(size:13,weight:.medium))
                TextField("Трек, исполнитель или альбом",text:$model.query).textFieldStyle(.plain).font(.system(size:12)).focused($searching).accessibilityLabel("Поиск по медиатеке")
                if model.query.isEmpty{Text("⌘K").font(.system(size:10,weight:.medium)).foregroundStyle(p.muted.opacity(0.75))}
                else{Button{model.query=""}label:{Image(systemName:"xmark.circle.fill")}.buttonStyle(.plain).accessibilityLabel("Очистить поиск")}
            }.foregroundStyle(p.muted).padding(.horizontal,13).frame(height:39).frame(maxWidth:325).background(p.panel,in:Capsule()).overlay(Capsule().strokeBorder(p.line,lineWidth:0.5))
            Spacer(minLength:4)
            Button{model.showConnect=true}label:{Label("Добавить папку",systemImage:"plus").font(.system(size:11,weight:.medium)).padding(.horizontal,13).padding(.vertical,11).background(p.panel,in:Capsule()).overlay(Capsule().strokeBorder(p.line,lineWidth:0.5))}
                .buttonStyle(SoftPressStyle()).foregroundStyle(p.text)
            Tool(icon:scheme == .dark ? "sun.max":"moon",label:"Переключить тему"){model.appearance=scheme == .dark ? "light":"dark"}
        }.padding(.horizontal,20).frame(height:71)
    }
    private var sidebar:some View {
        VStack(alignment:.leading,spacing:29) {
            Brand().padding(.horizontal,10).padding(.top,19).padding(.bottom,8)
            VStack(alignment:.leading,spacing:5) {
                navHeading("Медиатека")
                nav("Моя музыка",symbol:"square.stack.3d.up",selection:"library",count:model.tracks.count)
                nav("Исполнители",symbol:"person.2",selection:"artists")
                nav("Избранное",symbol:"heart",selection:"favorites",count:model.library.favorites.count)
            }
            VStack(alignment:.leading,spacing:6) {
                navHeading("Твои папки")
                ForEach(model.library.sources.filter{$0.provider == .yandex || $0.provider == .google}){source in sourceNav(source)}
                nav("На этом Mac",symbol:"laptopcomputer",selection:"local")
                Button{model.showConnect=true}label:{Label("Добавить папку",systemImage:"plus").font(.system(size:12)).frame(maxWidth:.infinity,alignment:.leading).padding(.horizontal,13).padding(.vertical,11)}
                    .buttonStyle(IconPressStyle()).foregroundStyle(p.muted)
            }
            Spacer(minLength:10)
            if model.downloadStates.values.contains(where:{$0.active}) {
                Button{model.navigate("downloads")}label:{HStack(spacing:9){ProgressView().controlSize(.mini);Text("Скачивание музыки").font(.system(size:11));Spacer()}}
                    .buttonStyle(.plain).foregroundStyle(p.green).padding(.horizontal,12)
            }
            VStack(alignment:.leading,spacing:19) {
                settingsLink
                HStack(spacing:8){Image(systemName:"waveform").foregroundStyle(p.green);Text("Твоя музыка.\nТвоё облако.").font(.system(size:10)).lineSpacing(3).foregroundStyle(p.muted)}
                    .padding(12).frame(maxWidth:.infinity,alignment:.leading).background(p.panel.opacity(0.6),in:RoundedRectangle(cornerRadius:13))
            }.padding(.horizontal,7).padding(.bottom,15)
        }.padding(.horizontal,12).frame(width:220).frame(maxHeight:.infinity).background(p.sidebar,in:RoundedRectangle(cornerRadius:21))
            .animation(reduceMotion ? nil:CMMotion.spring,value:model.selected)
    }
    @ViewBuilder private var settingsLink:some View {
        if #available(macOS 14.0,*) {
            SettingsLink{Label("Настройки",systemImage:"slider.horizontal.3").font(.system(size:12)).frame(maxWidth:.infinity,alignment:.leading)}.buttonStyle(.plain).foregroundStyle(p.muted)
        } else {
            Button{NSApp.sendAction(Selector(("showSettingsWindow:")),to:nil,from:nil)}label:{Label("Настройки",systemImage:"slider.horizontal.3").font(.system(size:12)).frame(maxWidth:.infinity,alignment:.leading)}.buttonStyle(.plain).foregroundStyle(p.muted)
        }
    }
    private func navHeading(_ title:String)->some View{Text(title.uppercased()).font(.system(size:9,weight:.semibold)).tracking(1.6).foregroundStyle(p.muted.opacity(0.7)).padding(.horizontal,13).padding(.bottom,8)}
    private func nav(_ title:String,symbol:String,selection:String,count:Int=0)->some View {
        Button{withAnimation(CMMotion.page){model.navigate(selection)}}label:{
            HStack(spacing:11) {
                Image(systemName:symbol).font(.system(size:15,weight:.regular)).frame(width:20).foregroundStyle(model.selected==selection ? p.green:p.muted)
                Text(title).font(.system(size:12,weight:model.selected==selection ? .semibold:.regular))
                Spacer()
                if count>0{Text("\(count)").font(.system(size:10,weight:.medium)).foregroundStyle(p.muted)}
            }.padding(.horizontal,13).padding(.vertical,12).background {
                if model.selected==selection{RoundedRectangle(cornerRadius:12).fill(p.selected.opacity(0.75)).matchedGeometryEffect(id:"navigation",in:navigation)}
            }.contentShape(RoundedRectangle(cornerRadius:12))
        }.buttonStyle(SoftPressStyle()).foregroundStyle(model.selected==selection ? p.text:p.muted)
    }
    private func sourceNav(_ source:MusicSource)->some View {
        Button{withAnimation(CMMotion.page){model.navigate("source:"+source.id)}}label:{
            HStack(spacing:10) {
                Image(systemName:source.provider.symbol).font(.system(size:15)).foregroundStyle(source.provider == .yandex ? Color(hex:0xd5b66e):Color(hex:0x8cadcf)).frame(width:20)
                VStack(alignment:.leading,spacing:4){Text(source.name).font(.system(size:12)).lineLimit(1);Text(source.provider.label).font(.system(size:10)).foregroundStyle(p.muted)}
                Spacer(minLength:0)
            }.padding(.horizontal,13).padding(.vertical,10).background {
                if model.selected=="source:"+source.id{RoundedRectangle(cornerRadius:12).fill(p.selected.opacity(0.75)).matchedGeometryEffect(id:"navigation",in:navigation)}
            }.contentShape(RoundedRectangle(cornerRadius:12))
        }.buttonStyle(SoftPressStyle()).foregroundStyle(p.text).accessibilityLabel("\(source.provider.label), \(source.name)")
            .contextMenu {
                Button("Обновить папку"){model.refresh(source)}
                Button("Скопировать ссылку"){NSPasteboard.general.clearContents();NSPasteboard.general.setString(source.url,forType:.string)}
                Divider()
                Button("Отключить папку",role:.destructive){model.remove(source)}
            }
    }
}
struct ConnectView:View {
    @EnvironmentObject private var model:LibraryModel
    @Environment(\.cm) private var p
    @State private var text=""
    var body:some View {
        VStack(alignment:.leading,spacing:23) {
            HStack {
                Image(systemName:"folder.badge.plus").font(.system(size:24,weight:.light)).foregroundStyle(p.green).frame(width:50,height:50).background(p.green.opacity(0.09),in:RoundedRectangle(cornerRadius:15))
                Spacer()
                Tool(icon:"xmark",label:"Закрыть"){model.showConnect=false}
            }
            VStack(alignment:.leading,spacing:11) {
                Text("Твоя следующая коллекция").font(.system(size:25,weight:.semibold)).tracking(-0.8)
                Text("Вставь публичную ссылку на свою папку.\nМы соберём музыку в удобную медиатеку.").font(.system(size:13)).lineSpacing(5).foregroundStyle(p.muted)
            }
            HStack(spacing:8){SourceBadge(title:"Яндекс Диск");SourceBadge(title:"Google Drive",icon:"triangle")}
            VStack(alignment:.leading,spacing:10) {
                Text("Ссылка на папку").font(.system(size:11,weight:.medium)).foregroundStyle(p.muted)
                HStack(spacing:10) {
                    TextField("https://disk.yandex.ru/d/…",text:$text).textFieldStyle(.plain).font(.system(size:13)).accessibilityLabel("Ссылка на папку").onSubmit{if !text.trimmed.isEmpty{model.connect(text)}}
                    Button("Вставить"){text=NSPasteboard.general.string(forType:.string) ?? ""}.buttonStyle(.plain).font(.system(size:11,weight:.medium)).foregroundStyle(p.green)
                }.padding(14).background(p.bg,in:RoundedRectangle(cornerRadius:12)).overlay(RoundedRectangle(cornerRadius:12).strokeBorder(p.line,lineWidth:1))
            }
            Button{model.connect(text)}label:{Text("Подключить папку").font(.system(size:13,weight:.semibold)).frame(maxWidth:.infinity).padding(14).background(p.green,in:Capsule()).foregroundStyle(p.onGreen)}
                .buttonStyle(SoftPressStyle()).disabled(text.trimmed.isEmpty).opacity(text.trimmed.isEmpty ? 0.4:1)
            Button{model.showConnect=false;DispatchQueue.main.asyncAfter(deadline:.now()+0.25){model.chooseLocal()}}label:{Label("Или выбрать музыку на Mac",systemImage:"laptopcomputer").font(.system(size:12)).foregroundStyle(p.muted).frame(maxWidth:.infinity)}.buttonStyle(SoftPressStyle())
        }.padding(32).frame(width:466).background(p.panel).foregroundStyle(p.text)
    }
}
struct SettingsView:View {
    @EnvironmentObject private var model:LibraryModel
    @EnvironmentObject private var artwork:ArtworkStore
    @Environment(\.cm) private var p
    var body:some View {
        Form {
            Picker("Оформление",selection:$model.appearance){Text("Тёмное").tag("dark");Text("Светлое").tag("light");Text("Как в системе").tag("system")}
            Section("Хранилище") {
                Text("Музыка сохраняется только по твоей команде. Локальные файлы открываются без копирования.").font(.system(size:12)).foregroundStyle(p.muted)
                HStack{Text("Кэш обложек — до 4 МиБ");Spacer();Button("Очистить"){artwork.clear()}}
                Button("Открыть скачанную музыку"){NSApp.keyWindow?.close();model.navigate("downloads")}
            }
            Section("Источники") {
                ForEach(model.library.sources){source in HStack{Label(source.name,systemImage:source.provider.symbol);Spacer();Button("Обновить"){model.refresh(source)};Button("Отключить"){model.remove(source)}}}
                Button("Подключить папку…"){NSApp.keyWindow?.close();DispatchQueue.main.async{model.showConnect=true;NSApp.activate(ignoringOtherApps:true)}}
            }
            Section{Text("Cloud Music \(Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "1.1.0") · macOS\nТвоя библиотека. Без аккаунта Cloud Music.").font(.system(size:11)).foregroundStyle(p.muted)}
        }.formStyle(.grouped).padding(16).frame(width:520,height:440).background(p.bg)
    }
}
struct WindowSetup:NSViewRepresentable {
    func makeNSView(context:Context)->NSView {
        let view=NSView()
        DispatchQueue.main.async{if let window=view.window{window.titlebarAppearsTransparent=true;window.titleVisibility = .hidden;window.isMovableByWindowBackground=true;window.backgroundColor = .windowBackgroundColor;window.identifier=NSUserInterfaceItemIdentifier("CloudMusicMainWindow")}}
        return view
    }
    func updateNSView(_ view:NSView,context:Context){}
}
@MainActor final class MusicDelegate:NSObject,NSApplicationDelegate {
    weak var model:LibraryModel?
    func applicationShouldTerminate(_ sender:NSApplication)->NSApplication.TerminateReply {
        guard let model else{return .terminateNow}
        Task{await model.flush();sender.reply(toApplicationShouldTerminate:true)}
        return .terminateLater
    }
}
struct ThemedSettingsView:View {
    @Environment(\.colorScheme) private var scheme
    var body:some View{SettingsView().environment(\.cm,Palette(dark:scheme == .dark))}
}
