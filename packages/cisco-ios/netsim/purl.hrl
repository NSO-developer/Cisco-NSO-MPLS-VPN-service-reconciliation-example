%    -*- Erlang -*-
%    Author:    Johan Bevemyr

-ifndef(URL_HRL).
-define(URL_HRL, true).

-record(hurl, {type,            %% net_path, abs_path or rel_path
               scheme    = [],
               host      = [],
               port,
               path      = [],
               user      = [],
               passwd    = [],
               params,
               qry,
               fragment}).

-record(http, {location,
               other = []}).

-record(hurl_opts, {timeout  = 5000,
                    sockopts = [],
                    dns_env}).

-endif.
