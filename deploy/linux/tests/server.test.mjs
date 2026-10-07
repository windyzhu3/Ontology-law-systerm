import test from 'node:test';
import assert from 'node:assert/strict';
import {route} from '../runtime/server.mjs';
test('one SPA includes business management and identity routes',()=>{
  for(const url of ['/workbench','/business/leads/intake','/management/team-tasks','/admin/identity/principals','/admin/audit']) assert.equal(route(url),'spa');
  assert.equal(route('/api/v1/session/context'),'api');
});
test('encoded traversal and arbitrary files are refused',()=>{
  for(const url of ['/../secrets','/%2e%2e/key','/assets/%2fkey','//foreign/x','/database/config.json']) assert.equal(route(url),'missing');
  assert.equal(route('/assets/app-123.js'),'asset');
});
