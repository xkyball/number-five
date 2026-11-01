import copy
import hashlib
import json
import os
import unittest
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from no5_pi_gateway.configuration import GatewayConfig, MapRoutesConfig, ConfigurationError
from no5_pi_gateway.service import create_app

ID = "11111111-1111-4111-8111-111111111111"
KEY = "map-route-acceptance-key"
def route(name="Test"):
    return {"name": name, "description": "foreign metadata", "feature": {"type": "Feature",
        "geometry": {"type": "LineString", "coordinates": [[18.1234567890123, -33.8], [18.2, -33.9]]},
        "properties": {"coordinatesMeta": [{"name": "A", "no5PointId": "p1"}, {"name": "B", "no5PointId": "p2"}]}}}

class MapRoutesTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.entries = {}; self.writes = 0; self.bad_readback = False; self.refuse_delete = False
        async def collection(request): return web.json_response(self.entries)
        async def resource(request):
            id = request.match_info["id"]
            if request.method == "PUT":
                self.writes += 1; self.entries[id] = await request.json()
                if self.bad_readback: self.entries[id]["name"] = "Different"
                return web.json_response({"state": "COMPLETED", "statusCode": 200})
            if request.method == "DELETE":
                if not self.refuse_delete: self.entries.pop(id, None)
                return web.json_response({"state": "COMPLETED", "statusCode": 200})
            if id not in self.entries: return web.json_response({}) # Signal K 2.13
            return web.json_response(self.entries[id])
        upstream = web.Application(); upstream.router.add_get("/signalk/v2/api/resources/routes", collection)
        upstream.router.add_route("*", "/signalk/v2/api/resources/routes/{id}", resource)
        self.upstream = TestServer(upstream); await self.upstream.start_server()
        self.env = patch.dict(os.environ, {"NO5_MAP_TEST_HASH": hashlib.sha256(KEY.encode()).hexdigest()}); self.env.start()
        self.client = TestClient(TestServer(await create_app(GatewayConfig(audio_gateway_key_hash_env="NO5_MAP_TEST_HASH",
            map_routes=MapRoutesConfig(True, str(self.upstream.make_url("/signalk/v2/api/resources/routes")))))))
        await self.client.start_server(); self.headers = {"X-No5-Gateway-Key": KEY}
    async def asyncTearDown(self):
        await self.client.close(); await self.upstream.close(); self.env.stop()
    async def save(self, value, token=None):
        return await self.client.put("/v1/map/routes/" + ID, json={"expectedToken": token, "route": value}, headers=self.headers)
    async def test_create_read_retry_and_exact_coordinate_order(self):
        result = await self.save(route()); self.assertEqual(200, result.status)
        created = await result.json(); self.assertEqual(route(), created["route"])
        self.assertEqual(200, (await self.save(route())).status); self.assertEqual(1, self.writes)
        listed = await (await self.client.get("/v1/map/routes", headers=self.headers)).json()
        self.assertEqual(created, listed["routes"][ID]); self.assertEqual([], listed["unavailableIds"])
    async def test_changed_remote_route_is_not_overwritten(self):
        initial = await (await self.save(route())).json()
        self.entries[ID]["name"] = "Changed by another app"
        rejected = await self.save(route("Edited"), initial["token"])
        self.assertEqual(409, rejected.status); self.assertEqual(1, self.writes)
        self.assertEqual("Changed by another app", self.entries[ID]["name"])
    async def test_delete_requires_current_token_and_confirmed_removal(self):
        first = await (await self.save(route())).json()
        async def delete(token):
            return await self.client.delete("/v1/map/routes/" + ID, json={"expectedToken": token}, headers=self.headers)
        self.assertEqual(400, (await delete(None)).status)
        self.entries[ID]["name"] = "Foreign change"
        self.assertEqual(409, (await delete(first["token"])).status)
        current = await (await self.client.get("/v1/map/routes", headers=self.headers)).json()
        token = current["routes"][ID]["token"]
        self.refuse_delete = True
        self.assertEqual(503, (await delete(token)).status); self.assertIn(ID, self.entries)
        self.refuse_delete = False
        self.assertEqual(200, (await delete(token)).status); self.assertNotIn(ID, self.entries)
        self.assertEqual(200, (await delete(token)).status) # lost-reply retry
    async def test_update_readback_and_foreign_properties_are_retained(self):
        first = await (await self.save(route())).json()
        changed = await self.save(route("Edited"), first["token"]); self.assertEqual(200, changed.status)
        confirmed = await changed.json(); self.assertNotEqual(first["token"], confirmed["token"])
        self.assertEqual("foreign metadata", confirmed["route"]["description"])
    async def test_denial_invalid_point_and_wrong_readback_do_not_ack(self):
        self.assertEqual(401, (await self.client.get("/v1/map/routes")).status)
        invalid = route(); invalid["feature"]["geometry"]["coordinates"].insert(1, [300, 0])
        self.assertEqual(400, (await self.save(invalid)).status); self.assertEqual(0, self.writes)
        self.bad_readback = True; self.assertEqual(503, (await self.save(route())).status)
    async def test_missing_or_unsupported_original_geometry_is_reported(self):
        self.entries[ID] = route(); self.entries[ID]["feature"]["geometry"]["type"] = "MultiLineString"
        result = await (await self.client.get("/v1/map/routes", headers=self.headers)).json()
        self.assertEqual({}, result["routes"]); self.assertEqual([ID], result["unavailableIds"])
    async def test_original_point_metadata_is_reported_without_partial_route(self):
        self.entries[ID] = route()
        self.entries[ID]["feature"]["properties"]["coordinatesMeta"][1]["no5PointId"] = "p1"
        result = await (await self.client.get("/v1/map/routes", headers=self.headers)).json()
        self.assertEqual({}, result["routes"]); self.assertEqual([ID], result["unavailableIds"])
    async def test_large_route_bound_is_specific_and_unknown_keys_are_rejected(self):
        value = route(); value["feature"]["geometry"]["coordinates"] = [[18 + i / 1000000, -33.8] for i in range(500)]
        self.assertGreater(len(json.dumps(value)), 4096); self.assertEqual(200, (await self.save(value)).status)
        result = await self.client.put("/v1/map/routes/" + ID, json={"route": route(), "expectedToken": None, "url": "http://other"}, headers=self.headers)
        self.assertEqual(400, result.status)

class ConfigTest(unittest.TestCase):
    def test_no_guessed_source_credentials_or_other_endpoint(self):
        self.assertFalse(MapRoutesConfig().enabled)
        for url in ("http://localhost:3000/other", "http://user:password@localhost:3000/signalk/v2/api/resources/routes", "http://localhost:3000/signalk/v2/api/resources/routes?url=other"):
            with self.assertRaises(ConfigurationError): MapRoutesConfig(True, url)
