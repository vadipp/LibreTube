package main

import (
 "bytes"
 "context"
 "encoding/json"
 "errors"
 "io"
 "log"
 "net/http"
 "net/url"
 "os"
 "os/exec"
 "regexp"
 "strconv"
 "strings"
 "sync"
 "syscall"
 "time"
)

type config struct{Backend,BackendHost,Probe,ProbeHost,Proxy,Extractor,Node,Port string; MaxHeight,Entries,Heap int; TTL,ExtractTimeout,RequestTimeout time.Duration}
func env(name,fallback string)string{if v:=os.Getenv(name);v!=""{return v};return fallback}
func settings()(config,error){
 c:=config{Backend:env("PIPED_BACKEND_URL","http://piped-backend:8080"),BackendHost:os.Getenv("PIPED_BACKEND_HOST"),Probe:os.Getenv("PROXY_PROBE_URL"),ProbeHost:os.Getenv("PROXY_PROBE_HOST"),Proxy:os.Getenv("PUBLIC_PROXY_URL"),Extractor:env("YT_DLP_PATH","/opt/yt-dlp"),Node:env("JS_RUNTIME_PATH","/usr/local/bin/node"),Port:env("PORT","8080")}
 integer:=func(key string,d,min,max int)(int,error){n,e:=strconv.Atoi(env(key,strconv.Itoa(d)));if e!=nil||n<min||n>max{return 0,errors.New("invalid configuration")};return n,nil}
 var e error;c.MaxHeight,e=integer("MAX_HEIGHT",1080,720,1080);if e!=nil{return c,e};c.Entries,e=integer("CACHE_ENTRIES",16,1,32);if e!=nil{return c,e};c.Heap,e=integer("SOLVER_HEAP_MB",96,48,128);if e!=nil{return c,e}
 for _,v:=range []struct{k string;d,min,max int;out *time.Duration}{{"CACHE_SECONDS",600,10,600,&c.TTL},{"EXTRACT_TIMEOUT_SECONDS",70,1,90,&c.ExtractTimeout},{"REQUEST_TIMEOUT_SECONDS",110,2,120,&c.RequestTimeout}}{n,e:=integer(v.k,v.d,v.min,v.max);if e!=nil{return c,e};*v.out=time.Duration(n)*time.Second}
 for _,s:=range []string{c.Backend,c.Probe,c.Proxy}{if s==""{continue};u,e:=url.Parse(s);if e!=nil||(u.Scheme!="http"&&u.Scheme!="https")||u.Host==""||u.User!=nil||(u.Path!=""&&u.Path!="/")||u.RawQuery!=""||u.Fragment!=""{return c,errors.New("invalid origin")}}
 n,e:=strconv.Atoi(c.Port);if e!=nil||n<1||n>65535{return c,errors.New("invalid port")};return c,nil
}
type document map[string]json.RawMessage
type result struct{Data document;Kind string;TTL time.Duration}
type entry struct{Result result;Until time.Time}
type flight struct{Done chan struct{};Result result;Err error}
type adapter struct{Config config;mu sync.Mutex;cache map[string]entry;flights map[string]*flight;active chan struct{};extract func(context.Context,string)(extraction,error);backend func(context.Context,string)(document,error);probe func(context.Context,string,string,int64,int64)([]byte,error)}
func newAdapter(c config)*adapter {a:=&adapter{Config:c,cache:map[string]entry{},flights:map[string]*flight{},active:make(chan struct{},1),probe:readRange};a.extract=a.runExtractor;a.backend=a.getBackend;return a}
var backendClient=&http.Client{Timeout:12*time.Second,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}
func(a *adapter)getBackend(ctx context.Context,id string)(document,error){req,e:=http.NewRequestWithContext(ctx,"GET",strings.TrimRight(a.Config.Backend,"/")+"/streams/"+id,nil);if e!=nil{return nil,e};if a.Config.BackendHost!=""{req.Host=a.Config.BackendHost};res,e:=backendClient.Do(req);if e!=nil{return nil,e};defer res.Body.Close();if res.StatusCode!=200{return nil,errors.New("backend unavailable")};b,e:=io.ReadAll(io.LimitReader(res.Body,4*1024*1024+1));if e!=nil||len(b)>4*1024*1024{return nil,errors.New("backend output limit")};var d document;e=json.Unmarshal(b,&d);return d,e}
type limitedOutput struct{bytes.Buffer}
func(b *limitedOutput)Write(p []byte)(int,error){if b.Len()+len(p)>8*1024*1024{return 0,errors.New("extractor output limit")};return b.Buffer.Write(p)}
func(a *adapter)runExtractor(ctx context.Context,id string)(extraction,error){
 ctx,cancel:=context.WithTimeout(ctx,a.Config.ExtractTimeout);defer cancel()
 cmd:=exec.CommandContext(ctx,a.Config.Extractor,"--ignore-config","--no-playlist","--no-warnings","--socket-timeout","15","--retries","0","--extractor-retries","0","--js-runtimes","node:"+a.Config.Node,"--dump-single-json","https://www.youtube.com/watch?v="+id)
 // Limit the third-party JS solver, not the Go server. The pinned EJS package is installed in the image.
 cmd.Env=[]string{"PATH="+os.Getenv("PATH"),"HOME=/tmp","TMPDIR=/tmp","XDG_CACHE_HOME=/tmp/cache","PYTHONDONTWRITEBYTECODE=1","NODE_OPTIONS=--max-old-space-size="+strconv.Itoa(a.Config.Heap)+" --max-semi-space-size=4"}
 cmd.SysProcAttr=&syscall.SysProcAttr{Setpgid:true};cmd.Cancel=func()error{if cmd.Process!=nil{return syscall.Kill(-cmd.Process.Pid,syscall.SIGKILL)};return nil};cmd.WaitDelay=time.Second
 var output limitedOutput;cmd.Stdout=&output;cmd.Stderr=io.Discard
 if e:=cmd.Run();e!=nil{return extraction{},errors.New("extractor failed")};var x extraction;e:=json.Unmarshal(output.Bytes(),&x);return x,e
}
func(a *adapter)build(id string)(result,error){
 ctx,cancel:=context.WithTimeout(context.Background(),a.Config.RequestTimeout);defer cancel();original,e:=a.backend(ctx,id);if e!=nil{return result{},e}
 var originals []map[string]any;var oldAudio []json.RawMessage
 if json.Unmarshal(original["videoStreams"],&originals)!=nil||json.Unmarshal(original["audioStreams"],&oldAudio)!=nil{return result{},errors.New("invalid backend response")}
 fallback:=func(ttl time.Duration)(result,error){return result{original,"fallback",ttl},nil}
 if string(original["livestream"])=="true"{return fallback(30*time.Second)}
 for _,s:=range originals{if raw,ok:=s["url"].(string);ok{u,e:=url.Parse(raw);if e!=nil||u.Query().Has("qhash"){return fallback(30*time.Second)}}}
 select{case a.active<-struct{}{}:defer func(){<-a.active}();default:return fallback(5*time.Second)}
 x,e:=a.extract(ctx,id);if e!=nil{return fallback(30*time.Second)};videos,audios,e:=selectFormats(x,a.Config.MaxHeight);if e!=nil{return fallback(30*time.Second)}
 base:=a.Config.Proxy;if base==""{_ = json.Unmarshal(original["proxyUrl"],&base)}
 expiry:=time.Now().Add(24*time.Hour)
 convert:=func(f format,audio bool)(stream,error){
  u,e:=googleMedia(f.URL);if e!=nil{return stream{},e};seconds,e:=strconv.ParseInt(u.Query().Get("expire"),10,64);expires:=time.Unix(seconds,0);if e!=nil||time.Until(expires)<=2*time.Minute{return stream{},errUnsupported}
  s,e:=metadata(f,x,ranges{},base,audio);if e!=nil{return stream{},e};probe,_:=url.Parse(s.URL);if a.Config.Probe!=""{p,_:=url.Parse(a.Config.Probe);probe.Scheme=p.Scheme;probe.Host=p.Host}
  b,e:=a.probe(ctx,probe.String(),a.Config.ProbeHost,0,131071);if e!=nil{return stream{},e};s.ranges,e=mp4Ranges(b);if e!=nil{return stream{},e};if expires.Before(expiry){expiry=expires};return s,nil
 }
 var newAudio []stream;for _,f:=range audios{if s,e:=convert(f,true);e==nil{newAudio=append(newAudio,s)}};if len(newAudio)==0{return fallback(30*time.Second)}
 // DownloadData identifies a representation by format+quality. Do not introduce ambiguous duplicates.
 var combined []map[string]any;keys:=map[string]bool{}
 for _,s:=range originals{if s["videoOnly"]==false{combined=append(combined,s);f,_:=s["format"].(string);q,_:=s["quality"].(string);keys[f+":"+q]=true}}
 var newVideo []any;hd:=false
 for _,f:=range videos{if s,e:=convert(f,false);e==nil&&!keys[s.Format+":"+s.Quality]{newVideo=append(newVideo,s);hd=hd||s.Height>=720}}
 if !hd||time.Until(expiry)<=2*time.Minute{return fallback(30*time.Second)};for _,s:=range combined{newVideo=append(newVideo,s)}
 data:=document{};for k,v:=range original{data[k]=v};data["videoStreams"]=rawJSON(newVideo);data["audioStreams"]=rawJSON(newAudio);data["hls"]=rawJSON(nil);data["dash"]=rawJSON(nil)
 ttl:=a.Config.TTL;if until:=time.Until(expiry)-2*time.Minute;until<ttl{ttl=until};return result{data,"enriched",ttl},nil
}
func(a *adapter)streams(ctx context.Context,id string)(result,error){
 a.mu.Lock();if x,ok:=a.cache[id];ok&&time.Now().Before(x.Until){a.mu.Unlock();return x.Result,nil};delete(a.cache,id)
 if f,ok:=a.flights[id];ok{a.mu.Unlock();select{case <-ctx.Done():return result{},ctx.Err();case <-f.Done:return f.Result,f.Err}}
 if len(a.flights)>=4{a.mu.Unlock();return result{},errors.New("busy")};f:=&flight{Done:make(chan struct{})};a.flights[id]=f;a.mu.Unlock()
 go func(){r,e:=a.build(id);a.mu.Lock();if e==nil&&r.TTL>0{b,_:=json.Marshal(r.Data);if len(b)<=1024*1024{if len(a.cache)>=a.Config.Entries{for key:=range a.cache{delete(a.cache,key);break}};a.cache[id]=entry{r,time.Now().Add(r.TTL)}}};f.Result=r;f.Err=e;delete(a.flights,id);close(f.Done);a.mu.Unlock()}()
 select{case <-ctx.Done():return result{},ctx.Err();case <-f.Done:return f.Result,f.Err}
}
var route=regexp.MustCompile(`^/downloads/streams/([A-Za-z0-9_-]{11})$`)
func(a *adapter)ServeHTTP(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Content-Type","application/json");w.Header().Set("Cache-Control","no-store");w.Header().Set("Access-Control-Allow-Origin","*");w.Header().Set("Access-Control-Allow-Methods","*");w.Header().Set("Access-Control-Allow-Headers","*, Authorization")
 send:=func(code int,v any){w.WriteHeader(code);_ = json.NewEncoder(w).Encode(v)}
 if r.Method=="GET"&&r.URL.RequestURI()=="/healthz"{send(200,map[string]string{"status":"ok"});return}
 match:=route.FindStringSubmatch(r.URL.EscapedPath());if len(match)==0||r.URL.RawQuery!=""{send(404,map[string]string{"error":"Not found"});return}
 if r.Method=="OPTIONS"{send(200,struct{}{});return};if r.Method!="GET"{send(405,map[string]string{"error":"Method not allowed"});return}
 value,e:=a.streams(r.Context(),match[1]);if e!=nil{send(503,map[string]string{"error":"Stream information temporarily unavailable"});return};w.Header().Set("X-Extractor-Source",value.Kind);send(200,value.Data)
}
func main(){
 if len(os.Args)==3&&os.Args[1]=="verify"{if e:=verify(os.Args[2]);e!=nil{log.Print(e);os.Exit(1)};return}
 if len(os.Args)==2&&os.Args[1]=="healthcheck"{client:=http.Client{Timeout:2*time.Second};r,e:=client.Get("http://127.0.0.1:"+env("PORT","8080")+"/healthz");if e!=nil{os.Exit(1)};defer r.Body.Close();if r.StatusCode!=200{os.Exit(1)};return}
 c,e:=settings();if e!=nil{log.Fatal("invalid adapter configuration")};a:=newAdapter(c);server:=http.Server{Addr:":"+c.Port,Handler:a,ReadHeaderTimeout:10*time.Second,ReadTimeout:15*time.Second,WriteTimeout:125*time.Second,IdleTimeout:30*time.Second,MaxHeaderBytes:8192};log.Print("extractor adapter listening");if e:=server.ListenAndServe();e!=nil{log.Fatal("adapter stopped")}
}
