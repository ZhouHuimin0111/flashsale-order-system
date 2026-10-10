import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'./scripts/ui',
  timeout:90000,
  expect:{timeout:10000},
  workers:1,
  reporter:[['list'],['html',{outputFolder:'test-results/ui-report',open:'never'}]],
  outputDir:'test-results/ui-artifacts',
  use:{baseURL:process.env.BASE_URL||'http://localhost:8080',browserName:'chromium',trace:'retain-on-failure',screenshot:'only-on-failure'},
  projects:[{name:'desktop',use:{viewport:{width:1440,height:1000}}},{name:'mobile',use:{viewport:{width:390,height:844}}}]
});
