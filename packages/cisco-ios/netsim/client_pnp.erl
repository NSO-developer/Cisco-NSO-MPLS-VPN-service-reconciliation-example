%    -*- Erlang -*-
%    Author:    Johan Bevemyr
%    Author:    Johan Nordlander

-module(client_pnp).
-author('jb@mail.tail-f.com').

-on_load(on_load/0).

-export([start/0]).

-define(i2l(X), integer_to_list(X)).
-define(l2i(X), list_to_integer(X)).
-define(l2b(X), list_to_binary(X)).
-define(l2f(X), list_to_float(X)).
-define(b2l(X), binary_to_list(X)).
-define(a2l(X), atom_to_list(X)).
-define(l2a(X), list_to_atom(X)).

on_load() ->
    proc_lib:spawn(fun start/0),
    ok.

start() ->
    case os:getenv("NETSIM_PNP_SERIAL") of
        false ->
            no_pnp;
        Serial ->
            HostPort = get_host_port(),
            pnp_hello(Serial, HostPort)
    end.

get_host_port() ->
    case os:getenv("NETSIM_PNP_HOST") of
        false ->
            Host = "pnpserver";
        Host ->
            ok
    end,
    case os:getenv("NETSIM_PNP_PORT") of
        false ->
            Port = "80";
        Port ->
            ok
    end,
    if Port == "80" ->
            Host;
       true ->
            Host++":"++Port
    end.

%% this loop should first do hello, then work request, and fake
%% device info and cli-cmd execution, and respect backoff
pnp_hello(Serial, HostPort) ->
    Xml = {info, [], ""},
    Res = do_post(HostPort, "HELLO", Xml),
    if Res == error ->
            timer:sleep(30*1000),
            pnp_hello(Serial, HostPort);
       true ->
            pnp_work_request(Serial, 1, HostPort)
    end.

pnp_work_request(Serial, Correlator, HostPort) ->
    % error_logger:format("Work request\n", []),
    Xml = build_work_request(Serial, Correlator),
    Res = do_post(HostPort, "WORK-REQUEST", Xml),
    IsDevInfo = find_tag(deviceInfo, Res),
    IsConfigApply = find_tag(configApply, Res),
    IsBackoff = find_tag(backoff, Res),
    % error_logger:format("** Result: ~p\n", [Res]),
    if IsDevInfo =/= false ->
            pnp_work_response('deviceInfo', Serial, Correlator, HostPort);
       IsConfigApply =/= false ->
            Cfg = find_tag('cli-config-data-block', Res),
            apply_cli_config(Cfg),
            pnp_work_response('configApply', Serial, Correlator, HostPort);
       IsBackoff =/= false ->
            pnp_delay(get_delay(Res,60), Serial, Correlator, HostPort);
       Res == error ->
            pnp_delay(60, Serial, Correlator, HostPort);
       true ->
            pnp_error(HostPort, Serial, Correlator)
    end.

apply_cli_config(false) ->
    ok;
apply_cli_config({_,_,[Cfg]}) ->
    try
        error_logger:format("Cfg=~p\n", [Cfg]),
        {ok, File} = misc:mktemp("cfg", file),
        file:write_file(File, "enable\nconfig terminal\n"++Cfg),
        {ip, [{_,Port}|_]} = confd_ia:get_address(),
        Res = os:cmd("cat "++File++" | confd_cli -I -n -u admin -P "++?i2l(Port)),
        error_logger:format("Res=~p\n", [Res]),
        ok
    catch
        X:Y ->
            StackTrace = erlang:get_stacktrace(),
            error_logger:format("apply_cli_config failed: ~p:~p\n~p\n",
                                [X,Y, StackTrace])
    end.

pnp_work_response(Req, Serial, Correlator, HostPort) ->
    % error_logger:format("Work response ~w\n", [Req]),
    Xml = build_work_response(Req, Serial, Correlator),
    Res = do_post(HostPort, "WORK-RESPONSE", Xml),
    IsByeBye = find_tag(bye, Res),
    IsBackoff = find_tag(backoff, Res),
    if IsByeBye =/= false ->
            pnp_delay(2, Serial, Correlator, HostPort);
       IsBackoff =/= false ->
            pnp_delay(get_delay(Res,60), Serial, Correlator, HostPort);
       Res == error ->
            pnp_delay(60, Serial, Correlator, HostPort);
       true ->
            pnp_error(HostPort, Serial, Correlator)
    end.

pnp_error(HostPort, Serial, Correlator) ->
    % error_logger:format("Pnp error\n", []),
    Xml = build_work_response(error, Serial, Correlator),
    _Res = do_post(HostPort, "WORK-RESPONSE", Xml),
    pnp_delay(60, Serial, Correlator, HostPort).

pnp_delay(never, _Serial, _Correlator, _HostPort) ->
    pnp_done;
pnp_delay(Delay, Serial, Correlator, HostPort) ->
    % error_logger:format("Pnp delay ~w\n", [Delay]),
    timer:sleep(Delay*1000),
    pnp_work_request(Serial, Correlator+1, HostPort).


do_post(HostPort, Cmd, Xml) ->
  WorkStr = lists:flatten(yaws_api:ehtml_expand(Xml)),
    case purl:post("http://"++HostPort++"/pnp/"++Cmd, WorkStr,
                   "text/xml; charset=utf-8") of
        {200, _Headers, Body} ->
        xml_parser:parse_xml(lists:concat(Body));
        {error, Reason} ->
            error_logger:format("pnp client got error on ~s: ~s\n",
                                [Cmd, Reason]),
      error
  end.

build_work_request(Serial, Corr) ->
    {pnp,[{xmlns,"urn:cisco:pnp"},
          {version,"1.0"},
          {udi, serial2udi(Serial)}],
     [{info,[{xmlns,"urn:cisco:pnp:work-info"},{correlator,udi_corr(Corr)}],
       [{deviceid,[],
         [{udi,[],[serial2udi(Serial)]},
          {authrequired,[],["false"]}]}]}]}.

build_work_response(deviceInfo, Serial, Corr) ->
    {_, Port} = confd_cfg:get([port, ssh, cli, confdConfig]),
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi, serial2udi(Serial)}],
     [{response,
       [{correlator,udi_corr(Corr)},
        {success,"1"},
        {xmlns,"urn:cisco:pnp:device-info"}],
       [{udi,[],
         [{'primary-chassis',[],[serial2udi(Serial)]}]},
        {imageinfo,[],
         [{versionstring,[],
           ["Cisco IOS Software, C2900 Software (C2900-UNIVERSALK9-M), "
            "Version 15.4(3)M, RELEASE SOFTWARE (fc1)\nTechnical Support: "
            "http://www.cisco.com/techsupport\nCopyright (c) 1986-2014 by "
            "Cisco Systems, Inc.\nCompiled Mon 21-Jul-14 19:29 by "
            "prod_rel_team"]},
          {imagefile,[],["flash0:c2900-universalk9-mz.SPA.154-3.M.bin"]},
          {imagehash,[],[]},
          {returntoromreason,[],["power-on"]},
          {bootvariable,[],[]},
          {bootldrvariable,[],[]},
          {configvariable,[],[]},
          {configreg,[],["0x2102"]},
          {configregnext,[],[]}]},
        {hardwareinfo,[],
         [{hostname,[],["Router"]},
          {vendor,[],["Cisco"]},
          {platformname,[],["CISCO2901/K9"]},
          {processortype,[],[]},
          {hwrevision,[],["1.0"]},
          {mainmemsize,[],["-1728053248"]},
          {iomemsize,[],["117440512"]},
          {boardid,[],[Serial]},
          {boardreworkid,[],[]},
          {processorrev,[],[]},
          {midplaneversion,[],[]},
          {location,[],[]}]},
        {filesystemlist,[],[{filesystem,[],[]},{filesystem,[],[]}]},
        {'netsim-port', [], [?i2l(Port)]}]}]};
build_work_response(configApply, Serial, Corr) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(Serial)}],
     [{response,
       [{xmlns,"urn:cisco:pnp:cli-config"},
        {correlator,udi_corr(Corr)},
        {success,"1"}],
       [{resultentry,
         [{linenumber,"1"},
          {clistring,"username admin privilege 15 password 0 admin\r"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"2"},{clistring,"hostname test"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"3"},{clistring,"ip domain-name tail-f.com"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"4"},
          {clistring,"crypto key generate rsa modulus 1024"}],
         [{success,[],[]},
          {text,[],
           ["\n**CLI Line # 4: The name for the keys will be: "
            "test.tail-f.com\r\n**CLI Line # 4: % The key modulus size is "
            "1024 bits\r\n**CLI Line # 4: % Generating 1024 bit RSA keys, "
            "keys will be non-exportable...\r\n**CLI Line # 4: [OK] "
            "(elapsed time was 2 seconds)\r"]}]},
        {resultentry,
         [{linenumber,"5"},{clistring,"username admin password admin"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"6"},{clistring,"enable secret secret"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"7"},{clistring,"ip ssh version 2"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"8"},{clistring,"line vty 0 4"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"9"},{clistring,"transport input ssh"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"10"},{clistring,"login local"}],
         [{success,[],[]}]}]}]};
build_work_response(error, Serial, _Corr) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(Serial)}],
     [{response,
       [{xmlns,"urn:cisco:pnp:fault"}],
       [{fault,[],
         [{faultcode,[],["XSVC:Client"]},
          {faultstring,[],["An unknown XML tag has been received"]},
          {detail,[],
           [{'xsvc-err:error',
             [{'xmlns:xsvc-err',"urn:cisco:pnp:error"}],
             [{'xsvc-err:details',[],["struct"]}]}]}]}]}]}.

udi_corr(Correlator) ->
    "udi"++?i2l(Correlator).

serial2udi(Serial) ->
    "PID:CISCO2901/K9,VID:V06,SN:"++Serial.

get_delay(Xml, Default) ->
  case find_tag(callbackAfter, Xml) of
      {_, _, Tags} ->
            try
                Hours = ?l2i(get_val(hours, Tags, "0")),
                Mins = ?l2i(get_val(minutes, Tags, "0")),
                Secs = ?l2i(get_val(seconds, Tags, "0")),
                V = (Hours*60 + Mins)*60 + Secs,
                if V > 0 -> V; true -> Default end
            catch _ ->
                Default
            end;
        false ->
      never
    end.

find_tag(_Tag, []) ->
    false;
find_tag(Tag, [T={Tag, _Attr, _Body}|_]) ->
    T;
find_tag(Tag, [T={Tag, _Attr}|_]) ->
    T;
find_tag(Tag, [{_Tag, _Attr}|Rest]) ->
    find_tag(Tag, Rest);
find_tag(Tag, [{_Tag, _Attr, Body}|Rest]) ->
    case find_tag(Tag, Body) of
        false ->
            find_tag(Tag, Rest);
        Elem ->
            Elem
    end;
find_tag(_Tag, _) ->
    false.

get_val(Key, L, Default) ->
    case lists:keysearch(Key, 1, L) of
        {value, {_, undefined}} -> Default;
        {value, {_, Val}} -> Val;
        {value, {_, _, [Val]}} -> Val;
        _ -> Default
    end.
