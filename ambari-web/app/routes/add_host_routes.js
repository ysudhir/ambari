/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

module.exports = Em.Route.extend({
  route: '/hosts/add',

  enter: function (router) {
    console.log('in /hosts/add:enter');

    Ember.run.next(function () {
      var addHostController = router.get('addHostController');
      App.ModalPopup.show({
        classNames: ['full-width-modal'],
        header:Em.I18n.t('hosts.add.header'),
        bodyClass:  App.AddHostView.extend({
          controllerBinding: 'App.router.addHostController'
        }),
        primary:Em.I18n.t('form.cancel'),
        secondary: null,
        showFooter: false,

        onPrimary:function () {
          this.hide();
          router.transitionTo('hosts.index');
        },
        onClose: function() {
          this.hide();
          router.transitionTo('hosts.index');
        }
      });
      router.transitionTo('step' + addHostController.get('currentStep'));
    });

  },

  /*connectOutlets: function (router, context) {
    console.log('in /hosts/add:connectOutlets');
    router.get('mainController').connectOutlet('addHost');
  },*/

  step1: Em.Route.extend({
    route: '/step1',
    connectOutlets: function (router) {
      console.log('in addHost.step1:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('1');
      controller.set('hideBackButton', true);
      controller.dataLoading().done(function () {
        controller.loadAllPriorSteps();
        controller.connectOutlet('wizardStep2', controller.get('content'));
      })
    },

    next: function (router) {
      var controller = router.get('addHostController');
      var wizardStep2Controller = router.get('wizardStep2Controller');
      wizardStep2Controller.patternExpression();
      controller.saveHosts(wizardStep2Controller);
      router.transitionTo('step2');
      App.db.setBootStatus(false);
    },
    evaluateStep: function (router) {
      console.log('in addHost.step1:evaluateStep');
      var addHostController = router.get('addHostController');
      var wizardStep2Controller = router.get('wizardStep2Controller');

      wizardStep2Controller.set('hasSubmitted', true);

      if (!wizardStep2Controller.get('isSubmitDisabled')) {
        wizardStep2Controller.evaluateStep();
      }
    }
  }),

  step2: Em.Route.extend({
    route: '/step2',
    connectOutlets: function (router) {
      console.log('in addHost.step2:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('2');
      controller.dataLoading().done(function () {
      controller.loadAllPriorSteps();
      controller.connectOutlet('wizardStep3', controller.get('content'));
      })
    },
    back: Em.Router.transitionTo('step1'),
    next: function (router, context) {
      var addHostController = router.get('addHostController');
      var wizardStep3Controller = router.get('wizardStep3Controller');
      addHostController.saveConfirmedHosts(wizardStep3Controller);
      addHostController.saveClients();

      App.db.setBootStatus(true);
      router.transitionTo('step3');
    },
    /**
     * Wrapper for remove host action.
     * Since saving data stored in addHostController, we should call this from router
     * @param router
     * @param context Array of hosts to delete
     */
    removeHosts: function (router, context) {
      console.log('in addHost.step2.removeHosts:hosts to delete ', context);
      var controller = router.get('addHostController');
      controller.removeHosts(context);
    }
  }),

  step3: Em.Route.extend({
    route: '/step3',
    connectOutlets: function (router, context) {
      console.log('in addHost.step3:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('3');
      controller.dataLoading().done(function () {
        controller.loadAllPriorSteps();
        controller.connectOutlet('wizardStep6', controller.get('content'));
      })
    },
    back: Em.Router.transitionTo('step2'),
    next: function (router) {
      var addHostController = router.get('addHostController');
      var wizardStep6Controller = router.get('wizardStep6Controller');

      if (wizardStep6Controller.validate()) {
        addHostController.saveSlaveComponentHosts(wizardStep6Controller);
        addHostController.get('content').set('serviceConfigProperties', null);
        App.db.setServiceConfigProperties(null);
        addHostController.loadAdvancedConfigs();
        router.transitionTo('step4');
      }
    }
  }),

  step4: Em.Route.extend({
    route: '/step4',
    connectOutlets: function (router) {
      console.log('in addHost.step4:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('4');
      controller.dataLoading().done(function () {
        controller.loadAllPriorSteps();
        controller.connectOutlet('wizardStep7', controller.get('content'));
      })
    },
    back: Em.Router.transitionTo('step3'),
    next: function (router) {
      var addHostController = router.get('addHostController');
      var wizardStep7Controller = router.get('wizardStep7Controller');
      addHostController.saveServiceConfigProperties(wizardStep7Controller);
      router.transitionTo('step5');
    }
  }),

  step5: Em.Route.extend({
    route: '/step5',
    connectOutlets: function (router, context) {
      console.log('in addHost.step5:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('5');
      controller.dataLoading().done(function () {
        controller.loadAllPriorSteps();
        controller.connectOutlet('wizardStep8', controller.get('content'));
      })
    },
    back: Em.Router.transitionTo('step4'),
    next: function (router) {
      var addHostController = router.get('addHostController');
      var wizardStep8Controller = router.get('wizardStep8Controller');
      addHostController.installServices();
      addHostController.setInfoForStep9();
      router.transitionTo('step6');
    }
  }),

  step6: Em.Route.extend({
    route: '/step6',
    connectOutlets: function (router, context) {
      console.log('in addHost.step6:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('6');
      controller.dataLoading().done(function () {
        controller.loadAllPriorSteps();
        if (!App.testMode) {              //if test mode is ON don't disable prior steps link.
          controller.setLowerStepsDisable(6);
        }
        controller.connectOutlet('wizardStep9', controller.get('content'));
      })
    },
    back: Em.Router.transitionTo('step5'),
    retry: function(router,context) {
      var addHostController = router.get('addHostController');
      var wizardStep9Controller = router.get('wizardStep9Controller');
      if (!wizardStep9Controller.get('isSubmitDisabled')) {
        if (wizardStep9Controller.get('content.cluster.status') !== 'START FAILED') {
        addHostController.installServices();
        addHostController.setInfoForStep9();
        } else {
          wizardStep9Controller.set('content.cluster.isCompleted', false);
        }
        wizardStep9Controller.navigateStep();
      }
    },
    unroutePath: function() {
      return false;
    },
    next: function (router) {
      var addHostController = router.get('addHostController');
      var wizardStep9Controller = router.get('wizardStep9Controller');
      addHostController.saveInstalledHosts(wizardStep9Controller);
      router.transitionTo('step7');
    }
  }),

  step7: Em.Route.extend({
    route: '/step7',
    connectOutlets: function (router, context) {
      console.log('in addHost.step7:connectOutlets');
      var controller = router.get('addHostController');
      controller.setCurrentStep('7');
      controller.dataLoading().done(function () {
        controller.loadAllPriorSteps();
        controller.connectOutlet('wizardStep10', controller.get('content'));
      })
    },
    back: Em.Router.transitionTo('step6'),
    complete: function (router, context) {
      if (true) {   // this function will be moved to installerController where it will validate
        var addHostController = router.get('addHostController');
        addHostController.finish();
        $(context.currentTarget).parents("#modal").find(".close").trigger('click');
      } else {
        console.log('cluster installation failure');
        //$(context.currentTarget).parents("#modal").find(".close").trigger('click');
      }
    }
  }),

  backToHostsList: function (router, event) {
    router.transitionTo('hosts.index');
  },

  gotoStep1: Em.Router.transitionTo('step1'),

  gotoStep2: Em.Router.transitionTo('step2'),

  gotoStep3: Em.Router.transitionTo('step3'),

  gotoStep4: Em.Router.transitionTo('step4'),

  gotoStep5: Em.Router.transitionTo('step5'),

  gotoStep6: Em.Router.transitionTo('step6'),

  gotoStep7: Em.Router.transitionTo('step7'),

  gotoStep8: Em.Router.transitionTo('step8'),

  gotoStep9: Em.Router.transitionTo('step9'),

  gotoStep10: Em.Router.transitionTo('step10')

});
