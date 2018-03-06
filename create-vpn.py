import _ncs
import ncs
from _ncs import maapi
import socket

V = _ncs.Value

def vpn_path(name) :
   return '/l3vpn:vpn/l3vpn{"' + name + '"}'

def create_service(t, name, asNumber) :
   path = vpn_path(name)

   t.safe_create(path)
   t.set_elem(V(asNumber, _ncs.C_UINT32), path + '/route-distinguisher')

def add_endpoint(t, serviceName, endpointId, ceDevice, ceInterface, ipNetwork, bandwidth, asNumber) :
   path = vpn_path(serviceName) + '/endpoint{"' + endpointId + '"}'

   t.safe_create(path)
   t.set_elem(ceDevice, path + '/ce-device')
   t.set_elem(ceInterface, path + '/ce-interface')
   t.set_elem(V(ipNetwork, _ncs.C_IPV4PREFIX), path + '/ip-network')
   t.set_elem(V(bandwidth, _ncs.C_UINT32), path + '/bandwidth')
   t.set_elem(V(asNumber, _ncs.C_UINT32), path + '/as-number')


def create_volvo(t) :
   serviceName = 'volvo'

   create_service(t, serviceName, 12345)

   add_endpoint(t, serviceName, 'branch-office1',
       'ce1', 'GigabitEthernet0/11', '10.7.7.0/24', 6000000, 65001)

   add_endpoint(t, serviceName, 'branch-office2',
       'ce4', 'GigabitEthernet0/18', '10.8.8.0/24', 6000000, 65002)

   add_endpoint(t, serviceName, 'main-office',
       'ce0', 'GigabitEthernet0/11', '10.10.1.0/24', 6000000,65003)

def sync_from_devices(maapiSock) :
   print('Syncing from devices ...')
   result = _ncs.maapi.request_action(maapiSock,[], 0, path = '/ncs:devices/sync-from')
   print('Synced from devices!')


if __name__ == '__main__' :
	sock_maapi = socket.socket()
	maapi.connect(
		sock_maapi,
		ip='127.0.0.1',
		port=_ncs.NCS_PORT)

	_ncs.maapi.start_user_session(
		sock_maapi,
		'admin',
		'python',
		[],
		'127.0.0.1',
		_ncs.PROTO_TCP)

	sync_from_devices(sock_maapi)

	sock_maapi.close()

	m = ncs.maapi.Maapi()
	m.start_user_session('admin', 'system', [])
	t = m.start_trans(ncs.RUNNING, ncs.READ_WRITE)

	create_volvo(t)
	t.apply()